/**
 * Licensed to Cloudera, Inc. under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Cloudera, Inc. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.thriftfs;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.StringTokenizer;
import java.util.Map;
import javax.security.auth.login.LoginException;

import org.apache.hadoop.conf.Configuration;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.UnixUserGroupInformation;
import org.apache.hadoop.util.VersionInfo;
import org.apache.hadoop.metrics.ContextFactory;
import org.apache.hadoop.metrics.spi.OutputRecord;

import org.apache.hadoop.thriftfs.api.HadoopServiceBase;
import org.apache.hadoop.thriftfs.api.MetricsContext;
import org.apache.hadoop.thriftfs.api.MetricsRecord;
import org.apache.hadoop.thriftfs.api.RequestContext;
import org.apache.hadoop.thriftfs.api.RuntimeInfo;
import org.apache.hadoop.thriftfs.api.StackTraceElement;
import org.apache.hadoop.thriftfs.api.ThreadStackTrace;

/**
 * Base class to provide some utility functions for thrift plugin handlers
 */
public abstract class ThriftHandlerBase implements HadoopServiceBase.Iface {
  protected final ThriftServerContext serverContext;
  static final Log LOG = LogFactory.getLog(ThriftHandlerBase.class);

  public ThriftHandlerBase(ThriftServerContext serverContext) {
    this.serverContext = serverContext;
  }

  /**
   * Return the version info of this server
   */
  public org.apache.hadoop.thriftfs.api.VersionInfo getVersionInfo(
    RequestContext ctx) {
    org.apache.hadoop.thriftfs.api.VersionInfo vi =
      new org.apache.hadoop.thriftfs.api.VersionInfo();
    vi.version = VersionInfo.getVersion();
    vi.revision = VersionInfo.getRevision();
    vi.compileDate = VersionInfo.getDate();
    vi.compilingUser = VersionInfo.getUser();
    vi.url = VersionInfo.getUrl();
    vi.buildVersion = VersionInfo.getBuildVersion();
    return vi;
  }

  /**
   * Return lots of status info about this server
   */
  public RuntimeInfo getRuntimeInfo(RequestContext ctx) {
    RuntimeInfo ri = new RuntimeInfo();
    ri.totalMemory = Runtime.getRuntime().totalMemory();
    ri.freeMemory = Runtime.getRuntime().freeMemory();
    ri.maxMemory = Runtime.getRuntime().maxMemory();

    return ri;
  }

  public List<MetricsContext> getAllMetrics(RequestContext reqCtx)
    throws org.apache.hadoop.thriftfs.api.IOException {
    List<MetricsContext> ret = new ArrayList<MetricsContext>();

    try {
      Collection<org.apache.hadoop.metrics.MetricsContext> allContexts = 
        ContextFactory.getFactory().getAllContexts();
      for (org.apache.hadoop.metrics.MetricsContext ctx : allContexts) {
        ret.add(metricsContextToThrift(ctx));
      }
    } catch (IOException ioe) {
      LOG.warn("getAllMetrics() failed", ioe);
      throw ThriftUtils.toThrift(ioe);
    }
    return ret;
  }

  public MetricsContext getMetricsContext(RequestContext context, String name)
    throws org.apache.hadoop.thriftfs.api.IOException {
    try {
      return metricsContextToThrift( ContextFactory.getFactory().getContext(name) );
    } catch (Throwable t) {
      LOG.warn("getMetricsContext(" + name + ") failed", t);
      throw ThriftUtils.toThrift(t);
    }
  }

  private MetricsContext metricsContextToThrift(
    org.apache.hadoop.metrics.MetricsContext ctx) {
    MetricsContext tCtx = new MetricsContext();
    tCtx.name = ctx.getContextName();
    tCtx.isMonitoring = ctx.isMonitoring();
    tCtx.period = ctx.getPeriod();
    tCtx.records = new HashMap<String, List<MetricsRecord>>();

    for (Map.Entry<String, Collection<OutputRecord>> entry :
           ctx.getAllRecords().entrySet()) {

      ArrayList<MetricsRecord> recs = new ArrayList<MetricsRecord>();
      for (OutputRecord outputRec : entry.getValue()) {
        MetricsRecord tRec = metricsRecordToThrift(outputRec);
        recs.add(tRec);
      }

      tCtx.records.put(entry.getKey(), recs);
    }

    return tCtx;
  }

  private MetricsRecord metricsRecordToThrift(OutputRecord outputRec) {
    MetricsRecord tRec = new MetricsRecord();

    // Thriftify tags
    tRec.tags = new HashMap<String, String>();
    for (Map.Entry<String, Object> tag : outputRec.getTagsCopy().entrySet()) {
      tRec.tags.put(tag.getKey(), String.valueOf(tag.getValue()));
    }

    // Thriftify metrics
    tRec.metrics = new HashMap<String, Long>();
    for (Map.Entry<String, Number> metric : outputRec.getMetricsCopy().entrySet()) {
      tRec.metrics.put(metric.getKey(), metric.getValue().longValue());
    }

    return tRec;
  }

  /**
   * Return a list of threads that currently exist with their stack traces
   */
  public List<ThreadStackTrace> getThreadDump(RequestContext ctx) {
    List<ThreadStackTrace> dump = new ArrayList<ThreadStackTrace>();

    Map<Thread, java.lang.StackTraceElement[]> traces = Thread.getAllStackTraces();
    for (Map.Entry<Thread, java.lang.StackTraceElement[]> entry : traces.entrySet()) {
      final Thread t = entry.getKey();
      final java.lang.StackTraceElement[] frames = entry.getValue();

      ThreadStackTrace tst = new ThreadStackTrace();
      tst.threadName = t.getName();
      tst.threadStringRepresentation = String.valueOf(t);
      tst.isDaemon = t.isDaemon();
      tst.stackTrace = new ArrayList<StackTraceElement>();
      for (java.lang.StackTraceElement ste : frames) {
        StackTraceElement tFrame = new StackTraceElement();
        tFrame.className = ste.getClassName();
        tFrame.fileName = ste.getFileName();
        tFrame.lineNumber = ste.getLineNumber();
        tFrame.methodName = ste.getMethodName();
        tFrame.isNativeMethod = ste.isNativeMethod();
        tFrame.stringRepresentation = String.valueOf(ste);
        tst.stackTrace.add(tFrame);
      }
      dump.add(tst);
    }
    return dump;
  }


  /**
   * Should be called by all RPCs on the request context passed in.
   * This assumes the authentication role of the requester.
   */
  protected void assumeUserContext(RequestContext ctx) {
    UserGroupInformation ugi = null;
    if (ctx != null && ctx.confOptions != null) {
      Configuration conf = new Configuration(false);
      
      for (Map.Entry<String, String> entry : ctx.confOptions.entrySet()) {
        conf.set(entry.getKey(), entry.getValue());
      }
      // UnixUserGroupInformation.readFromConf() caches UGIs based on
      // on username, so it's not usable if the group list
      // may be changing.
      String[] ugiparts = conf.getStrings(UnixUserGroupInformation.UGI_PROPERTY_NAME);
      ugi = new UnixUserGroupInformation(ugiparts);
    } else {
      LOG.warn("Did not receive UGI information with call.");
      // For backwards-compatibility, continue, though this really
      // should be an error.
      ugi = new UnixUserGroupInformation(new String[] { "nobody", "nogroup" });
    }
    UserGroupInformation.setCurrentUser(ugi);
    LOG.info("Connection from user " + ugi);
  }

  /**
   * Take on the credentials of the current process, which,
   * since this is running inside of a Hadoop daemon, is
   * the cluster's superuser.  If for some reason this
   * fails, this will return nobody/nogroup.
   */
  protected void assumeSuperuserContext() {
    UserGroupInformation ugi = null;
    try {
      ugi = UnixUserGroupInformation.login();
    } catch (LoginException e) {
      LOG.info("Cannot get current UNIX user: " + e.getMessage());
      ugi = new UnixUserGroupInformation(new String[] { "nobody", "nogroup" });
    }
    UserGroupInformation.setCurrentUser(ugi);
  }
}
