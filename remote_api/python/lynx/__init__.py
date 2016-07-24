'''Python interface for the LynxKite Remote API.

The access to the LynxKite instance can be configured through the following environment variables:

    LYNXKITE_ADDRESS=https://lynxkite.example.com/
    LYNXKITE_USERNAME=user@company
    LYNXKITE_PASSWORD=my_password

Example usage:

    import lynx
    lk = lynx.LynxKite()
    p = lk.new_project()
    p.newVertexSet(size=100)
    print(p.scalar('vertex_count'))

The list of operations is not documented, but you can copy the invocation from a LynxKite project
history.
'''
import http.cookiejar
import json
import os
import sys
import types
import urllib

if sys.version_info.major < 3:
  raise Exception('At least Python version 3 is needed!')

default_sql_limit = 1000
default_privacy = 'public-read'


class LynxKite(object):
  '''A connection to a LynxKite instance.

  Some LynxKite API methods take a connection argument which can be used to communicate with
  multiple LynxKite instances from the same session. If no arguments to the constructor are
  provided, then a connection is created using the following environment variables:
  LYNXKITE_ADDRESS, LYNXKITE_USERNAME, and LYNXKITE_PASSWORD.
  '''

  def __init__(self, username=None, password=None, address=None):
    '''Creates a connection object, performing authentication if necessary.'''
    self.address = address or os.environ['LYNXKITE_ADDRESS']
    self.username = username or os.environ.get('LYNXKITE_USERNAME')
    self.password = password or os.environ.get('LYNXKITE_PASSWORD')
    cj = http.cookiejar.CookieJar()
    self.opener = urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(cj))
    if username:
      self.login()

  def login(self):
    self.request(
        '/passwordLogin',
        dict(
            username=self.username,
            password=self.password))

  def request(self, endpoint, payload={}):
    '''Sends an HTTP request to LynxKite and returns the response when it arrives.'''
    data = json.dumps(payload).encode('utf-8')
    req = urllib.request.Request(
        self.address.rstrip('/') + '/' + endpoint.lstrip('/'),
        data=data,
        headers={'Content-Type': 'application/json'})
    max_tries = 3
    for i in range(max_tries):
      try:
        with self.opener.open(req) as r:
          return r.read().decode('utf-8')
      except urllib.error.HTTPError as err:
        if err.code == 401 and i + 1 < max_tries:  # Unauthorized.
          self.login()
          # And then retry via the "for" loop.
        elif err.code == 500:  # Internal server error.
          raise LynxException(err.read())
        else:
          raise err

  def send(self, command, payload={}, raw=False):
    '''Sends a command to LynxKite and returns the response when it arrives.'''
    data = self.request('/remote/' + command, payload)
    if raw:
      r = json.loads(data)
    else:
      r = json.loads(data, object_hook=_asobject)
    return r

  def sql(self, query, limit=None, **kwargs):
    '''Runs global level SQL query with the syntax: lynx.sql("select * from `x|vertices`", x=p, limit=10),
    where p is an object that has a checkpoint, and giving the limit is optional'''
    checkpoints = {}
    for name, p in kwargs.items():
      checkpoints[name] = p.checkpoint
    r = self.send('globalSQL', dict(
        query=query,
        checkpoints=checkpoints
    ))
    return View(self, r.checkpoint)

  def get_directory_entry(self, path):
    return self.send('getDirectoryEntry', dict(path=path))

  def import_csv(
          self,
          files,
          table,
          privacy=default_privacy,
          columnNames=[],
          delimiter=',',
          mode='FAILFAST',
          infer=True,
          columnsToImport=[],
          view=False):
    return self._import_or_create_view(
        "CSV",
        view,
        dict(table=table,
             files=files,
             privacy=privacy,
             columnNames=columnNames,
             delimiter=delimiter,
             mode=mode,
             infer=infer,
             columnsToImport=columnsToImport))

  def import_hive(
          self,
          table,
          hiveTable,
          privacy=default_privacy,
          columnsToImport=[]):
    return self._import_or_create_view(
        "Hive",
        view,
        dict(
            table=table,
            privacy=privacy,
            hiveTable=hiveTable,
            columnsToImport=columnsToImport))

  def import_jdbc(
          self,
          table,
          jdbcUrl,
          jdbcTable,
          keyColumn,
          privacy=default_privacy,
          columnsToImport=[],
          view=False):
    return self._import_or_create_view(
        "Jdbc",
        view,
        dict(table=table,
             jdbcUrl=jdbcUrl,
             privacy=privacy,
             jdbcTable=jdbcTable,
             keyColumn=keyColumn,
             columnsToImport=columnsToImport))

  def import_parquet(
          self,
          table,
          privacy=default_privacy,
          columnsToImport=[],
          view=False):
    return self._import_or_create_view(
        "Parquet",
        view,
        dict(table=table,
             privacy=privacy,
             columnsToImport=columnsToImport))

  def import_orc(
          self,
          table,
          privacy=default_privacy,
          columnsToImport=[],
          view=False):
    return self._import_or_create_view(
        "ORC",
        view,
        dict(table=table,
             privacy=privacy,
             columnsToImport=columnsToImport))

  def import_json(
          self,
          table,
          privacy=default_privacy,
          columnsToImport=[],
          view=False):
    return self._import_or_create_view(
        "Json",
        view,
        dict(table=table,
             privacy=privacy,
             columnsToImport=columnsToImport))

  def _import_or_create_view(self, format, view, dict):
    if view:
      res = self.send('createView' + format, dict)
      return View(self.lk, res.checkpoint)
    else:
      res = self.send('import' + format, dict)
      return Table(self.lk, res.checkpoint)

  def load_project(self, name):
    '''Loads an existing LynxKite project.'''
    r = self.send('loadProject', dict(name=name))
    return Project(self, r.checkpoint)

  def load_table(self, name):
    '''Loads an existing LynxKite table.'''
    r = self.send('loadTable', dict(name=name))
    return Table(self, r.checkpoint)

  def load_view(self, name):
    '''Loads an existing LynxKite view.'''
    r = self.send('loadView', dict(name=name))
    return View(self, r.checkpoint)

  def new_project(self):
    '''Creates a new unnamed empty LynxKite project.'''
    r = self.send('newProject')
    return Project(self, r.checkpoint)


class Table(object):

  def __init__(self, lynxkite, checkpoint):
    self.lk = lynxkite
    self.checkpoint = checkpoint
    self.name = '!checkpoint(%s,)|vertices' % checkpoint

  def save(self, name):
    self.lk.send('saveTable', dict(
        checkpoint=self.checkpoint,
        name=name))


class View:

  def __init__(self, lynxkite, checkpoint):
    self.lk = lynxkite
    self.checkpoint = checkpoint

  def save(self, name):
    self.lk.send('saveView', dict(
        checkpoint=self.checkpoint,
        name=name))

  def take(self, limit):
    r = self.lk.send('takeFromView', dict(
        checkpoint=self.checkpoint,
        limit=limit,
    ), raw=True)
    return r['rows']

  def export_csv(self, path, header=True, delimiter=',', quote='"'):
    self.lk.send('exportViewToCSV', dict(
        checkpoint=self.checkpoint,
        path=path,
        header=header,
        delimiter=delimiter,
        quote=quote,
    ))

  def export_json(self, path):
    self.lk.send('exportViewToJson', dict(
        checkpoint=self.checkpoint,
        path=path,
    ))

  def export_orc(self, path):
    self.lk.send('exportViewToORC', dict(
        checkpoint=self.checkpoint,
        path=path,
    ))

  def export_parquet(self, path):
    self.lk.send('exportViewToParquet', dict(
        checkpoint=self.checkpoint,
        path=path,
    ))

  def export_jdbc(self, url, table, mode='error'):
    '''Exports the view into a database table via JDBC.

    The "mode" argument describes what happens if the table already exists. Valid values are
    "error", "overwrite", and "append".
    '''
    self.lk.send('exportViewToJdbc', dict(
        checkpoint=self.checkpoint,
        jdbcUrl=url,
        table=table,
        mode=mode,
    ))

  def to_table(self):
    res = self.lk.send('exportViewToTable', dict(checkpoint=self.checkpoint))
    return Table(self.lk, res.checkpoint)


class Project(object):
  '''Represents an unanchored LynxKite project.

  This project is not automatically saved to the LynxKite project directories.
  '''

  def __init__(self, lynxkite, checkpoint):
    '''Creates a new blank project.'''
    self.lk = lynxkite
    self.checkpoint = checkpoint

  def save(self, name):
    self.lk.send(
        'saveProject',
        dict(
            checkpoint=self.checkpoint,
            project=name))

  def scalar(self, scalar):
    '''Fetches the value of a scalar. Returns either a double or a string.'''
    r = self.lk.send(
        'getScalar',
        dict(
            checkpoint=self.checkpoint,
            scalar=scalar))
    if hasattr(r, 'double'):
      return r.double
    return r.string

  def sql(self, query, limit=None):
    r = self.lk.send('globalSQL', dict(
        query=query,
        checkpoints={'': self.checkpoint},
    ))
    return View(self.lk, r.checkpoint)

  def run_operation(self, operation, parameters):
    '''Runs an operation on the project with the given parameters.'''
    r = self.lk.send(
        'runOperation',
        dict(
            checkpoint=self.checkpoint,
            operation=operation,
            parameters=parameters))
    self.checkpoint = r.checkpoint
    return self

  def compute(self):
    return self.lk.send(
        'computeProject', dict(checkpoint=self.checkpoint))

  def __getattr__(self, attr):
    '''For any unknown names we return a function that tries to run an operation by that name.'''
    def f(**kwargs):
      params = {}
      for k, v in kwargs.items():
        params[k] = str(v)
      return self.run_operation(attr, params)
    return f


class LynxException(Exception):

  '''Raised when LynxKite indicates that an error has occured while processing a command.'''

  def __init__(self, error):
    super(LynxException, self).__init__(error)
    self.error = error


def _asobject(dic):
  '''Wraps the dict in a namespace for easier access. I.e. d["x"] becomes d.x.'''
  return types.SimpleNamespace(**dic)