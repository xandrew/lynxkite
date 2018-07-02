import pandas as pd
import unittest
import lynx.kite
from lynx.kite import pp, text, workspace


class TestLazyWorkspaceDecorator(unittest.TestCase):

  def test_simplest(self):
    lk = lynx.kite.LynxKite()

    @workspace
    def select(x, column):
      return x.sql1(sql=f'select id, {column} from vertices')

    eg = lk.createExampleGraph()
    output = select(eg, 'name')
    expected = pd.DataFrame(
        {'id': [0., 1., 2., 3.], 'name': ['Adam', 'Eve', 'Bob', 'Isolated Joe']})
    pd.testing.assert_frame_equal(output.df(), expected, check_like=True)

  def test_multiple_instances(self):
    lk = lynx.kite.LynxKite()

    @workspace
    def select(x, column):
      return x.sql1(sql=f'select id, {column} from vertices')

    eg = lk.createExampleGraph()
    names = select(eg, 'name')
    ages = select(eg, 'age')
    result = lk.sql('select name, age from one join two where one.id = two.id', ages, names, ages)
    expected = pd.DataFrame({
        'name': ['Adam', 'Eve', 'Isolated Joe', 'Bob'],
        'age': [20.3, 18.2, 2, 50.3]})
    pd.testing.assert_frame_equal(result.df(), expected, check_like=True)
    self.assertEqual(ages.name(), 'select - box_1')
    self.assertEqual(names.name(), 'select - box_2')

  def test_input_naming(self):
    lk = lynx.kite.LynxKite()

    @workspace
    def f(i, *j, k, **l):
      return i

    eg = lk.createExampleGraph()
    result = f(eg, eg, eg, k=eg, l=eg, m=eg)
    self.assertEqual(list(result.inputs.keys()), ['i', 'j_1', 'j_2', 'k', 'l_l', 'l_m'])
    # The workspace name is only finalized upon save.
    self.assertEqual(result.name(), 'f{unique_id}')
    result.sql('select * from vertices').df()
    self.assertEqual(result.name(), 'f - box_1')

  def test_varargs(self):
    lk = lynx.kite.LynxKite()

    @workspace
    def f1(i, *inputs):
      return inputs[i]

    eg = lk.createExampleGraph()
    empty = lk.createVertices(size=0)
    result = f1(2, empty, empty, eg, empty).sql('select name from vertices')
    expected = pd.DataFrame({'name': ['Adam', 'Eve', 'Bob', 'Isolated Joe']})
    pd.testing.assert_frame_equal(result.df(), expected, check_like=True)

    @workspace
    def f2(i, **inputs):
      return inputs[i]

    eg = lk.createExampleGraph()
    empty = lk.createVertices(size=0)
    result = f2('b', a=empty, b=eg).sql('select name from vertices')
    pd.testing.assert_frame_equal(result.df(), expected, check_like=True)

  def test_ws_param(self):
    lk = lynx.kite.LynxKite()

    @workspace(parameters=[text('name')])
    def f(t):
      return t.sql1(sql=pp('select age from vertices where name == "$name"'))

    eg = lk.createExampleGraph()
    result = f(eg, name='Bob')
    expected = pd.DataFrame({'age': [50.3]})
    pd.testing.assert_frame_equal(result.df(), expected, check_like=True)

  def test_multi_output(self):
    lk = lynx.kite.LynxKite()

    @workspace
    def f(t):
      return dict(
          age=t.sql1(sql='select age from vertices limit 1'),
          name=t.sql1(sql='select name from vertices limit 1'))

    eg = lk.createExampleGraph()
    result = f(eg)
    pd.testing.assert_frame_equal(result['age'].df(), pd.DataFrame({'age': [20.3]}))
    pd.testing.assert_frame_equal(result['name'].df(), pd.DataFrame({'name': ['Adam']}))