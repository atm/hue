// Licensed to Cloudera, Inc. under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  Cloudera, Inc. licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.cloudera.beeswax;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.HttpURLConnection;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.Vector;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.Schema;
import org.apache.hadoop.hive.ql.Driver;
import org.apache.hadoop.hive.ql.exec.FetchTask;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.metadata.Hive;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.parse.BaseSemanticAnalyzer;
import org.apache.hadoop.hive.ql.parse.ExplainSemanticAnalyzer;
import org.apache.hadoop.hive.ql.plan.fetchWork;
import org.apache.hadoop.hive.ql.plan.tableDesc;
import org.apache.hadoop.hive.ql.processors.CommandProcessor;
import org.apache.hadoop.hive.ql.processors.CommandProcessorFactory;
import org.apache.hadoop.hive.ql.QueryPlan;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hadoop.hive.serde.Constants;
import org.apache.hadoop.security.UnixUserGroupInformation;
import org.apache.log4j.Logger;
import org.apache.thrift.TException;

import com.cloudera.beeswax.api.BeeswaxException;
import com.cloudera.beeswax.api.BeeswaxService;
import com.cloudera.beeswax.api.ConfigVariable;
import com.cloudera.beeswax.api.Query;
import com.cloudera.beeswax.api.QueryExplanation;
import com.cloudera.beeswax.api.QueryHandle;
import com.cloudera.beeswax.api.QueryNotFoundException;
import com.cloudera.beeswax.api.QueryState;
import com.cloudera.beeswax.api.Results;
import com.cloudera.beeswax.api.ResultsMetadata;

/**
 * Implementation of BeeswaxService interface.
 */
public class BeeswaxServiceImpl implements BeeswaxService.Iface {
  // We wrap each query in its own little Runnable, then use the executor to run them.
  private ExecutorService executor;

  // runningQueries keeps track of the concurrently running queries, with the query id as key.
  private ConcurrentHashMap<String, RunningQueryState> runningQueries;

  private String notifyUrl;

  /** Mapping between configuration variable names and descriptions. */
  private ConfigDescriptions configDescriptions = ConfigDescriptions.get();

  private static final long RUNNING_QUERY_LIFETIME = 7*24*60*60*1000;  // 1 week
  private static final long EVICTION_INTERVAL = 3*60*60*1000;  // 3 hours
  private static final String NOTIFY_URL_BASE = "/beeswax/query_cb/done/";

  private static Logger LOG = Logger.getLogger(BeeswaxServiceImpl.class.getName());

  /**
   * To be read and modified while holding a lock on the state object.
   *
   * Essentially, this annotates a Driver with a SessionState object.
   * These go together, but SessionState is accessed via a thread-local,
   * so manual management has to happen.
   *
   * The state changes are...
   *   CREATED -> INITIALIZED -> COMPILED -> RUNNING -> FINISHED
   * EXCEPTION is also valid.
   */
  private class RunningQueryState {
    private int state = QueryState.CREATED;
    // Thread local used by Hive quite a bit.
    private SessionState sessionState;
    private Throwable exception;
    private Driver driver;
    private ByteArrayOutputStream errStream = new ByteArrayOutputStream();
    private ByteArrayOutputStream outStream = new ByteArrayOutputStream();
    private long startRow = 0;
    private HiveConf hiveConf = null;
    private final Query query;
    private long atime = 0;
    private LogContext logContext;
    /** The client handle, if applicable */
    private QueryHandle handle = null;

    /**
     * Create an instance with the given query and LogContext.
     * @param query  The Beeswax Thrift Query object.
     * @param logContext  The context to associate with.
     */
    public RunningQueryState(Query query, LogContext logContext) {
      this.query = query;
      this.atime = System.currentTimeMillis();
      this.logContext = logContext;
    }

    public String toString() {
      return "RunningQueryState" + (handle == null ? "" : " id: " + handle.id) +
             " started " + new SimpleDateFormat().format(new Date(atime)) +
             "; (state " + state + "); query: " + query.query;
    }

    public long getAtime() {
      return this.atime;
    }

    public HiveConf getHiveConf() {
      return hiveConf;
    }

    public void setQueryHandle(QueryHandle handle) {
      this.handle = handle;
    }

    public QueryHandle getQueryHandle() {
      return handle;
    }

    public String getInfoStreamAsString() {
      sessionState.out.flush();
      sessionState.childOut.flush();
      try {
        return outStream.toString("UTF-8");
      } catch (UnsupportedEncodingException e) {
        throw new RuntimeException(e);
      }
    }

    public String getErrorStreamAsString() {
      sessionState.err.flush();
      sessionState.childErr.flush();
      try {
        return errStream.toString("UTF-8");
      } catch (UnsupportedEncodingException e) {
        throw new RuntimeException(e);
      }
    }

    synchronized public void compile() throws BeeswaxException {
      assertState(QueryState.INITIALIZED);
      checkedCompile();
      state = QueryState.COMPILED;
    }

    private void assertState(int expected) {
      if (state != expected) {
        throw new IllegalStateException(String.format("Expected %s, but state is: %s",
            expected, state));
      }
    }

    /**
     * Set the state to EXCEPTION and remember the Throwable that is causing it.
     * Then rethrow the exception.
     */
    private <T extends Throwable> void throwException(T ex) throws T {
      saveException(ex);
      throw ex;
    }

    private void checkedCompile() throws BeeswaxException {
      // Run through configuration commands
      for (String cmd : query.configuration) {
        // This is pretty whacky; SET and ADD get treated differently
        // than CREATE TEMPORARY FUNCTION...  The trimming logic
        // here is borrowed from CliDriver; oy.
        String cmd_trimmed = cmd.trim();
        String[] tokens = cmd_trimmed.split("\\s+");
        String cmd1 = cmd_trimmed.substring(tokens[0].length()).trim();
        CommandProcessor p = CommandProcessorFactory.get(tokens[0]);
        int res;
        if (p instanceof Driver) {
          res = p.run(cmd);
        } else {
          res = p.run(cmd1);
        }
        if (res != 0) {
          throwException(new RuntimeException(getErrorStreamAsString()));
        }
      }

      // Note that driver.compile() talks to HDFS, so it's
      // not as quick as one might think.
      int compileRes = driver.compile(query.query);
      if (compileRes != 0) {
        throwException(new BeeswaxException(getErrorStreamAsString(),
                                            this.logContext.getName(),
                                            this.handle));
      }
    }

    /**
     * Separate from constructor, because initialize() may
     * need to be called in a separate thread.
     */
    synchronized void initialize() {
      assertState(QueryState.CREATED);
      this.hiveConf = new HiveConf(Driver.class);

      // Update configuration with user/group info.
      if (query.hadoop_user == null) {
        throw new RuntimeException("User must be specified.");
      }
      StringBuilder ugi = new StringBuilder();
      ugi.append(query.hadoop_user);
      for (String group : query.hadoop_groups) {
        ugi.append(",");
        ugi.append(group);
      }
      hiveConf.set(UnixUserGroupInformation.UGI_PROPERTY_NAME, ugi.toString());

      // Update scratch dir (to have one per user)
      File scratchDir = new File("/tmp/hive-beeswax-" + query.hadoop_user);
      hiveConf.set(HiveConf.ConfVars.SCRATCHDIR.varname, scratchDir.getPath());
      // Create the temporary directory if necessary.
      // If mapred.job.tracker is set to local, this is used by MapRedTask.
      if (!scratchDir.isDirectory()) {
        if (scratchDir.exists() || !scratchDir.mkdirs()) {
          LOG.warn("Could not create tmp dir:" + scratchDir);
        }
      }

      driver = new Driver(hiveConf);
      SessionState.start(hiveConf); // this is thread-local
      this.sessionState = SessionState.get();

      // If this work has a LogContext, associate the children output to the logContext
      OutputStream lcOutStream = null;
      if (this.logContext != null)
        lcOutStream = this.logContext.getOutputStream();

      // A copy of everything goes to the LogContext.
      // In addition, stderr goes to errStream for error reporting.
      // Note that child output is explicitly tee to System.{out,err},
      // otherwise it'll be swallowed by outStream.
      this.sessionState.out = new PrintStream(new TeeOutputStream(lcOutStream, this.outStream));
      this.sessionState.err = new PrintStream(new TeeOutputStream(lcOutStream, this.errStream));
      this.sessionState.childOut =
          new PrintStream(new TeeOutputStream(System.out, sessionState.out));
      this.sessionState.childErr =
          new PrintStream(new TeeOutputStream(System.err, sessionState.err));

      this.state = QueryState.INITIALIZED;
    }

    /**
     * Executes query. Updates state. (QueryState variable can be polled.)
     * @throws BeeswaxException
     */
    public void execute() throws BeeswaxException {
      synchronized (this) {
        assertState(QueryState.COMPILED);
        state = QueryState.RUNNING;
      }
      int ret = driver.execute();
      try {
        synchronized (this) {
          assertState(QueryState.RUNNING);
          if (ret == 0) {
            state = QueryState.FINISHED;
          } else {
            throwException(new BeeswaxException("Driver returned: " + ret
                + ".  Errors: " + getErrorStreamAsString(), logContext.getName(),
                this.handle));
          }
        }
      } finally {
        notifyDone(this);
      }
    }

    public void bringUp() {
      SessionState.start(this.sessionState);
    }

    private void materializeResults(Results r, boolean startOver) throws IOException {
      if (driver.getPlan().getPlan().getFetchTask() == null) {
        // This query is never going to return anything.
        r.has_more = false;
        r.setData(Collections.<String>emptyList());
        r.setColumns(Collections.<String>emptyList());
        return;
      }

      if (startOver) {
        // This is totally inappropriately reaching into internals.
        driver.getPlan().getPlan().getFetchTask().initialize(hiveConf,
            driver.getPlan());
        startRow = 0;
      }
      Vector<String> v = new Vector<String>();
      r.setData(v);
      r.has_more = driver.getResults(v);
      r.start_row = startRow;
      startRow += v.size();

      r.setColumns(new ArrayList<String>());
      try {
        for (FieldSchema f : driver.getSchema().getFieldSchemas()) {
          r.addToColumns(f.getName());
        }
      } catch (Exception e) {
        // An empty partitioned table may not have table description
        LOG.error("Error getting column names of results.", e);
      }
    }

    /**
     * Get the result schema and misc metadata, in the context of SELECT.
     */
    synchronized public ResultsMetadata getResultMetadata() {
      Schema schema = null;
      try {
        schema = driver.getSchema();
      } catch (Exception ex) {
        LOG.error("Error getting schema for query: " + query.query, ex);
      }

      fetchWork work = getFetchWork();
      tableDesc desc = work.getTblDesc();
      String tabledir = null;
      String tablename = null;
      String sep = null;
      if (work != null) {
        tabledir = work.getTblDir();
      }
      if (desc != null) {
        sep = desc.getProperties().getProperty(
                                        Constants.SERIALIZATION_FORMAT,
                                        "" + Utilities.ctrlaCode);
        tablename = desc.getTableName();
      }
      return new ResultsMetadata(schema, tabledir, tablename, sep);
    }

    /**
     * Get the fetchWork. Only SELECTs have them.
     */
    synchronized private fetchWork getFetchWork() {
      QueryPlan plan = driver.getPlan();
      FetchTask fetchTask = null;
      if (plan != null) {
        BaseSemanticAnalyzer sem = plan.getPlan();
        if (sem.getFetchTask() != null) {
          if (!sem.getFetchTaskInit()) {
            sem.setFetchTaskInit(true);
            sem.getFetchTask().initialize(hiveConf, plan);
          }
          fetchTask = (FetchTask) sem.getFetchTask();
        }
      }

      if (fetchTask == null) {
        return null;
      }

      fetchWork work = (fetchWork) fetchTask.getWork();
      return work;
    }

    synchronized public QueryExplanation explain() throws BeeswaxException {
      assertState(QueryState.INITIALIZED);
      // By manipulating the query, this will make errors harder to find.
      query.query = "EXPLAIN " + query.query;
      checkedCompile();
      if (!(driver.getPlan().getPlan() instanceof ExplainSemanticAnalyzer)) {
        throwException(new RuntimeException("Expected explain plan."));
      }

      int ret;
      if (0 != (ret = driver.execute())) {
        throwException(new RuntimeException("Failed to execute: EXPLAIN " + ret));
      }
      StringBuilder sb = new StringBuilder();
      Vector<String> v = new Vector<String>();
      try {
        while (driver.getResults(v)) {
          for (String s : v) {
            sb.append(s);
            sb.append("\n");
          }
          v.clear();
        }
      } catch (IOException e) {
        throwException(new RuntimeException(e));
      } finally {
        // Don't let folks re-use the state object.
        state = QueryState.FINISHED;
      }
      return new QueryExplanation(sb.toString());
    }

    public Results fetch(boolean fromBeginning) throws BeeswaxException {
      this.atime = System.currentTimeMillis();
      Results r = new Results();
      // Only one person can access a running query at a time.
      synchronized(this) {
        switch(state) {
        case QueryState.RUNNING:
          r.ready = false;
          break;
        case QueryState.FINISHED:
          bringUp();
          r.ready = true;
          try {
            materializeResults(r, fromBeginning);
          } catch (IOException e) {
            throw new BeeswaxException(e.toString(), logContext.getName(), handle);
          }
          break;
        case QueryState.EXCEPTION:
          if (exception instanceof BeeswaxException) {
            throw (BeeswaxException) exception;
          } else {
            throw new BeeswaxException(exception.toString(), logContext.getName(), handle);
          }
        }
      }
      return r;
    }

    /** Store the first exception we see. */
    private void saveException(Throwable t) {
      synchronized (this) {
        if (state != QueryState.EXCEPTION) {
          state = QueryState.EXCEPTION;
          exception = t;
        }
      }
    }

    /**
     * Submits this query to the given executor to be run.
     *
     * @param executor
     * @param lc
     */
    void submitTo(ExecutorService executor, final LogContext lc) {
      final RunningQueryState state = this;
      executor.submit(new Runnable() {
        @Override
        public void run() {
          try {
            lc.registerCurrentThread();
            try {
              Hive.closeCurrent();
              Hive.get(state.hiveConf);
            } catch (HiveException ex) {
              throw new RuntimeException(ex);
            }
            state.bringUp();
            state.execute();
          } catch (Throwable t) {
            LOG.error("Exception while processing query", t);
            state.saveException(t);
          }
        }
      });
    }
  }


  /**
   * Notify Desktop that this query has finished, by sending a GET request
   * to a specific URL. We expect Desktop that view to always return HTTP_OK,
   * so that we know the notification has been delivered to the right view.
   * (We don't care whether the notification handling fails or succeeds.)
   */
  void notifyDone(RunningQueryState state) {
    QueryHandle handle = state.getQueryHandle();
    if (handle == null) {
      LOG.error("Finished execution of a query without a handle: " + state.toString());
      return;
    }

    String urlString = notifyUrl + handle.id;

    try {
      URL url = new URL(urlString);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("GET");
      conn.connect();
      if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
        throw new IOException("Desktop returns error: " + conn.getResponseMessage());
      }

      LOG.debug("Notified query done at " + url);
    } catch (IOException ioe) {
      LOG.error("Error when notifying Desktop at " + urlString, ioe);
    }
  }


  /**
   * Create a new BeeswaxServiceImpl.
   * @param dtHost  The Hue host (ip or hostname).
   * @param dtPort  The port Desktop runs on.
   * @param dtHttps  Whether Desktop is running https.
   */
  public BeeswaxServiceImpl(String dtHost, int dtPort, boolean dtHttps) {
    LogContext.initLogCapture();
    this.executor = Executors.newCachedThreadPool(new NamingThreadFactory("Beeswax-%d"));
    this.runningQueries = new ConcurrentHashMap<String, RunningQueryState>();

    String protocol = dtHttps ? "https" : "http";
    this.notifyUrl = protocol + "://" + dtHost + ":" + dtPort + NOTIFY_URL_BASE;

    // A daemon thread that periodically evict stale RunningQueryState objects
    Thread evicter = new Thread(new Runnable() {
        @Override
        public void run() {
          while (true) {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, RunningQueryState> entry : runningQueries.entrySet()) {
              RunningQueryState rqState = entry.getValue();
              if (rqState.getAtime() + RUNNING_QUERY_LIFETIME < now) {
                String id = entry.getKey();
                runningQueries.remove(id);
                LOG.debug("Removed " + rqState.toString());
                Thread.yield();                 // be nice
              }
            }

            LogContext.garbageCollect(RUNNING_QUERY_LIFETIME);

            long wakeup = now + EVICTION_INTERVAL;
            while (System.currentTimeMillis() < wakeup) {
              try {
                Thread.sleep(EVICTION_INTERVAL);
              } catch (InterruptedException e) { }
            }
          }
        }
    }, "Evicter");
    evicter.setDaemon(true);
    evicter.start();
  }

  /**
   * Submit a query and return a handle (QueryHandle). The query runs asynchronously.
   * Queries can be long-lasting, so we push the execution into a new state.
   * Compiling happens in the current context so we report errors early.
   */
  @Override
  public QueryHandle query(final Query query) throws BeeswaxException {
    // First, create an id and reset the LogContext
    String uuid = UUID.randomUUID().toString();
    final QueryHandle handle = new QueryHandle(uuid, uuid);
    final LogContext lc = LogContext.registerCurrentThread(handle.log_context);
    lc.resetLog();

    // Make an administrative record
    final RunningQueryState state = new RunningQueryState(query, lc);
    state.setQueryHandle(handle);
    runningQueries.put(handle.id, state);
    state.initialize();
    // All kinds of things can go wrong when we compile it. So catch all.
    try {
      state.compile();
    } catch (BeeswaxException perr) {
      state.saveException(perr);
      throw perr;
    } catch (Throwable t) {
      state.saveException(t);
      throw new BeeswaxException(t.toString(), handle.log_context, handle);
    }
    // Now spin off the query.
    state.submitTo(executor, lc);

    return handle;
  }

  /**
   * Verify that the handle data is not null.
   */
  private void validateHandle(QueryHandle handle) throws QueryNotFoundException {
    if (handle == null) {
      LOG.error("Encountered null QueryHandle");
      throw new QueryNotFoundException();
    }
    if (handle.id == null || handle.log_context == null) {
      LOG.error("Invalid QueryHandle: id " + handle.id + "; log context " + handle.log_context);
      throw new QueryNotFoundException();
    }
  }

  @Override
  public String echo(String s) throws TException {
    return s;
  }

  /**
   * Get the query plan for a query.
   */
  @Override
  public QueryExplanation explain(Query query) throws BeeswaxException, TException {
    String contextName = UUID.randomUUID().toString();
    LogContext lc = LogContext.registerCurrentThread(contextName);
    RunningQueryState state = new RunningQueryState(query, lc);
    state.initialize();
    // All kinds of things can go wrong when we compile it. So catch all.
    QueryExplanation exp;
    try {
      exp = state.explain();
    } catch (BeeswaxException perr) {
      throw perr;
    } catch (Throwable t) {
      throw new BeeswaxException(t.toString(), contextName, null);
    }
    // On success, we remove the LogContext
    LogContext.destroyContext(contextName);
    return exp;
  }

  /**
   * Get the results of a query. This is non-blocking. Caller should check
   * Results.ready to determine if the results are in yet.
   *
   * @param handle  The handle from query()
   * @param fromBeginning  If true, rewind to the first row. Otherwise fetch from last position.
   */
  @Override
  public Results fetch(QueryHandle handle, boolean fromBeginning)
      throws QueryNotFoundException, BeeswaxException {
    LogContext.unregisterCurrentThread();
    validateHandle(handle);
    LogContext.registerCurrentThread(handle.log_context);
    RunningQueryState state = runningQueries.get(handle.id);
    if (state == null) {
      throw new QueryNotFoundException();
    }
    Results res = state.fetch(fromBeginning);
    return res;
  }

  @Override
  public String dump_config() throws TException {
    HiveConf c = new HiveConf();
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    try {
      c.writeXml(b);
      return new String(b.toByteArray(), "UTF-8");
    } catch (IOException e) {
      throw new TException(e);
    }
  }

  /**
   * Get the state of the query
   *
   * @param handle The handle from query()
   */
  @Override
  public int get_state(QueryHandle handle) throws QueryNotFoundException {
    LogContext.unregisterCurrentThread();
    validateHandle(handle);
    LogContext.registerCurrentThread(handle.log_context);
    RunningQueryState state = runningQueries.get(handle.id);
    if (state == null) {
      throw new QueryNotFoundException();
    }
    return state.state;
  }

  /**
   * Get the results metadata
   *
   * @param handle
   */
  @Override
  public ResultsMetadata get_results_metadata(QueryHandle handle) throws QueryNotFoundException {
    LogContext.unregisterCurrentThread();
    validateHandle(handle);
    LogContext.registerCurrentThread(handle.log_context);
    RunningQueryState state = runningQueries.get(handle.id);
    if (state == null) {
      throw new QueryNotFoundException();
    }
    return state.getResultMetadata();
  }

  /**
   * Get the log messages related to the context.
   *
   * @param contextName The log context name
   * @return The log message as a string.
   */
  @Override
  public String get_log(String contextName) throws QueryNotFoundException, TException {
    final boolean DONT_CREATE = false;
    LogContext.unregisterCurrentThread();
    if (contextName == null) {
      throw new QueryNotFoundException();
    }
    LogContext lc = LogContext.getByName(contextName, DONT_CREATE);
    if (lc == null)
      throw new QueryNotFoundException();
    return lc.readLog();
  }

  /*
   * This is similar in spirit to Hive's own SetProcessor
   */
  @Override
  public List<ConfigVariable> get_default_configuration(boolean includeHadoop)
      throws TException {
    HiveConf conf = new HiveConf(BeeswaxServiceImpl.class);
    Properties p;
    if (includeHadoop) {
      p = conf.getAllProperties();
    } else {
      p = conf.getChangedProperties();
    }
    List<ConfigVariable> ret = new ArrayList<ConfigVariable>();
    for (Entry<Object, Object> e : p.entrySet()) {
      String key = (String)e.getKey();
      String value = (String)e.getValue();

      ConfigVariable cv = new ConfigVariable();
      cv.setKey(key);
      cv.setValue(value);
      cv.setDescription(configDescriptions.lookup(key));
      ret.add(cv);
    }
    return ret;
  }
}
