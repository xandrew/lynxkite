package com.lynxanalytics.biggraph.frontend_operations

import com.lynxanalytics.biggraph.SphynxOnly
import com.lynxanalytics.biggraph.graph_api.Scripting._
import com.lynxanalytics.biggraph.graph_api.GraphTestUtils._

class DerivePythonTest extends OperationsTestBase {
  test("example graph", SphynxOnly) {
    val p = box("Create example graph")
      .box("Compute in Python", Map(
        "inputs" -> "vs.name, vs.age, es.weight, es.comment, es.src, es.dst, scalars.greeting",
        "outputs" -> "vs.with_title: str, vs.age_squared: float, es.score: float, es.names: str, scalars.hello: str, scalars.average_age: float",
        "code" -> """
vs['with_title'] = 'The Honorable ' + vs.name
vs['age_squared'] = vs.age ** 2
es['score'] = es.weight + es.comment.str.len()
es['names'] = 'from ' + vs.name[es.src].values + ' to ' + vs.name[es.dst].values
scalars.hello = scalars.greeting.lower()
scalars.average_age = vs.age.mean()
          """))
      .project
    assert(
      get(p.vertexAttributes("with_title").runtimeSafeCast[String]) ==
        Map(0 -> "The Honorable Adam", 1 -> "The Honorable Eve", 2 -> "The Honorable Bob", 3 -> "The Honorable Isolated Joe"))
    assert(
      get(p.vertexAttributes("age_squared").runtimeSafeCast[Double]).mapValues(_.round) ==
        Map(0 -> 412, 1 -> 331, 2 -> 2530, 3 -> 4))
    assert(
      get(p.edgeAttributes("score").runtimeSafeCast[Double]) ==
        Map(0 -> 15.0, 1 -> 16.0, 2 -> 18.0, 3 -> 17.0))
    assert(
      get(p.edgeAttributes("names").runtimeSafeCast[String]) ==
        Map(0 -> "from Adam to Eve", 1 -> "from Eve to Adam", 2 -> "from Bob to Adam", 3 -> "from Bob to Eve"))
    assert(get(p.scalars("hello").runtimeSafeCast[String]) == "hello world! 😀 ")
    assert(get(p.scalars("average_age").runtimeSafeCast[Double]).round == 23)
  }
}
