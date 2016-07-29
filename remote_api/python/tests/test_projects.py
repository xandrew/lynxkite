import unittest
import lynx
import os


class TestProjects(unittest.TestCase):

  def test_save_and_load_project(self):
    lk = lynx.LynxKite()
    p = lk.new_project()
    p.save('test_project')
    p2 = lk.load_project('test_project')
    self.assertTrue(p2.checkpoint is not None)
    self.assertEqual(p.checkpoint, p2.checkpoint)

  def test_get_scalar(self):
    lk = lynx.LynxKite()
    p = lk.new_project()
    p.exampleGraph()
    greeting = p.scalar('greeting')
    self.assertEqual(greeting, 'Hello world!')

  def test_force(self):
    p = lynx.LynxKite().new_project()
    p.newVertexSet(size=5000)
    p.compute()

if __name__ == '__main__':
  unittest.main()
