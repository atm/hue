#!/usr/bin/env python
# Licensed to Cloudera, Inc. under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  Cloudera, Inc. licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""
Authentication backend classes for Desktop.

These classes should implement the interface described at:
  http://docs.djangoproject.com/en/1.0/topics/auth/#writing-an-authentication-backend

In addition, the User classes they return must support:
 - get_primary_group() (returns a string)
 - get_groups() (returns a list of strings)
 - get_home_directory() (returns None or a string)
 - has_desktop_permission(action, app) -> boolean
Because Django's models are sometimes unfriendly, you'll want
User to remain a django.contrib.auth.models.User object.

In Desktop, only one authentication backend may be specified.
"""
from django.contrib.auth.models import User
import django.contrib.auth.backends
import logging
import desktop.conf
from django.utils.importlib import import_module
from django.core.exceptions import ImproperlyConfigured

LOG = logging.getLogger(__name__)

def load_augmentation_class():
  """
  Loads the user augmentation class.
  Similar in spirit to django.contrib.auth.load_backend
  """
  try:
    class_name = desktop.conf.AUTH.USER_AUGMENTOR.get()
    i = class_name.rfind('.')
    module, attr = class_name[:i], class_name[i+1:]
    mod = import_module(module)
    klass = getattr(mod, attr)
    LOG.info("Augmenting users with class: %s" % (klass,))
    return klass
  except:
    raise ImproperlyConfigured("Could not find user_augmentation_class: %s" % (class_name,))

_user_augmentation_class = None
def get_user_augmentation_class():
  global _user_augmentation_class

  if _user_augmentation_class is None:
    _user_augmentation_class = load_augmentation_class()
  return _user_augmentation_class

def rewrite_user(user):
  """
  Rewrites the user according to the augmentation class.
  We currently only re-write specific attributes,
  though this could be generalized.
  """
  augment = get_user_augmentation_class()(user)
  for attr in ("get_groups", "get_primary_group",
      "get_home_directory", "has_desktop_permission"):
    setattr(user, attr, getattr(augment, attr))
  return user

class DefaultUserAugmentor(object):
  def __init__(self, parent):
    self._parent = parent

  def get_groups(self):
    return [ self._parent.username ]

  def get_primary_group(self):
    return self._parent.username

  def get_home_directory(self):
    return "/user/%s" % self._parent.username

  def has_desktop_permission(self, action, app):
    return True

def find_or_create_user(username, password=None):
  try:
    user = User.objects.get(username=username)
    LOG.debug("Found user %s in the db" % username)
  except User.DoesNotExist:
    LOG.info("Materializing user %s in the database" % username)
    user = User(username=username)
    if password is None:
      user.set_unusable_password()
    else:
      user.set_password(password)
    user.is_superuser = True
    user.save()
  return user

class DesktopBackendBase(object):
  """
  Abstract base class for providing external authentication schemes.

  Extend this class and implement check_auth
  """
  def authenticate(self, username, password):
    if self.check_auth(username, password):
      user = find_or_create_user(username)
      user = rewrite_user(user)
      return user
    else:
      return None

  def get_user(self, user_id):
    try:
      user = User.objects.get(pk=user_id)
      user = rewrite_user(user)
      return user
    except User.DoesNotExist:
      return None

  def check_auth(self, username, password):
    """
    Implementors should return a boolean value which determines
    whether the given username and password pair is valid.
    """
    raise Exception("Abstract class - must implement check_auth")

class AllowFirstUserDjangoBackend(django.contrib.auth.backends.ModelBackend):
  """
  Allows the first user in, but otherwise delegates to Django's
  ModelBackend.
  """
  def authenticate(self, username=None, password=None):
    user = super(AllowFirstUserDjangoBackend, self).authenticate(username, password)
    if user is not None:
      if user.is_active:
        user = rewrite_user(user)
        return user
      return None

    if self.is_first_login_ever():
      user = find_or_create_user(username, password)
      user = rewrite_user(user)
      return user

    return None

  def get_user(self, user_id):
    user = super(AllowFirstUserDjangoBackend, self).get_user(user_id)
    user = rewrite_user(user)
    return user

  def is_first_login_ever(self):
    """ Return true if no one has ever logged in to Desktop yet. """
    return User.objects.count() == 0

class AllowAllBackend(DesktopBackendBase):
  """
  Authentication backend that allows any user to login as long
  as they have a username and password of any kind.
  """
  def check_auth(self, username, password):
    return True

  @classmethod
  def manages_passwords_externally(cls):
    return True
