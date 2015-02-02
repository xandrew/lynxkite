package com.lynxanalytics.biggraph.graph_api

import java.io.File
import java.util.UUID
import org.apache.commons.io.FileUtils
import org.scalatest.FunSuite

import com.lynxanalytics.biggraph.TestUtils

class MetaGraphManagerTest extends FunSuite with TestMetaGraphManager {
  test("Basic application flow works as expected.") {
    val manager = cleanMetaManager

    // We can add two dependent operation.
    val firstInstance = manager.apply(new CreateSomeGraph())
    val firstVertices = firstInstance.outputs.vertexSets('vertices)
    val firstEdges = firstInstance.outputs.edgeBundles('edges)
    val firstVattr = firstInstance.outputs.vertexAttributes('vattr)
    val firstEattr = firstInstance.outputs.vertexAttributes('eattr)

    val secondInstance = manager.apply(
      new FromVertexAttr(),
      MetaDataSet(
        vertexSets = Map('inputVertices -> firstVertices),
        vertexAttributes = Map('inputAttr -> firstVattr)))
    val secondAttrValues = secondInstance.outputs.vertexSets('attrValues)
    val secondLinks = secondInstance.outputs.edgeBundles('links)

    // All entities of both operations are available in the manager by guid.
    firstInstance.entities.all.values.foreach { entity =>
      assert(manager.entity(entity.gUID) == entity)
    }
    secondInstance.entities.all.values.foreach { entity =>
      assert(manager.entity(entity.gUID) == entity)
    }

    // VertexSets and EdgeBundles are linked as expected.
    assert(firstEdges.srcVertexSet == firstVertices)
    assert(firstEdges.dstVertexSet == firstVertices)
    assert(secondLinks.srcVertexSet == secondAttrValues)
    assert(secondLinks.dstVertexSet == firstVertices)
    assert(manager.incomingBundles(firstVertices).toSet == Set(firstEdges, secondLinks))
    assert(manager.outgoingBundles(firstVertices).toSet == Set(firstEdges))
    assert(manager.incomingBundles(secondAttrValues).toSet == Set())
    assert(manager.outgoingBundles(secondAttrValues).toSet == Set(secondLinks))

    // Properties are linked as expected.
    assert(firstVattr.vertexSet == firstVertices)
    assert(firstEattr.vertexSet == firstEdges.asVertexSet)
    assert(manager.attributes(firstVertices).toSet == Set(firstVattr))
    assert(manager.attributes(firstEdges).toSet == Set(firstEattr))

    // Dependent operations linked as expected.
    assert(manager.dependentOperations(firstVertices).contains(secondInstance))
    assert(manager.dependentOperations(firstVattr).contains(secondInstance))
  }

  test("Sometimes, there is no such component") {
    val manager = cleanMetaManager
    val instance = manager.apply(new CreateSomeGraph())
    intercept[java.util.NoSuchElementException] {
      manager.entity(new UUID(0, 0))
    }
  }

  test("Save and load works") {
    val m1Dir = cleanMetaManagerDir
    val m1o = VersioningMetaGraphManager(m1Dir)
    val m2o = cleanMetaManager

    val firstInstance = m1o.apply(new CreateSomeGraph())
    val firstVertices = firstInstance.outputs.vertexSets('vertices)
    val firstVattr = firstInstance.outputs.vertexAttributes('vattr)
    val secondInstance = m1o.apply(
      new FromVertexAttr(),
      MetaDataSet(
        vertexSets = Map('inputVertices -> firstVertices),
        vertexAttributes = Map('inputAttr -> firstVattr)))

    m1o.setTag("my/favorite/vertices/first", firstVertices)

    val m1c = VersioningMetaGraphManager(m1Dir)

    (firstInstance.entities.all.values ++ secondInstance.entities.all.values).foreach { entity =>
      // We have an entity of the GUID of all entities.
      val clonedEntity = m1c.entity(entity.gUID)
      // They look similar.
      assert(clonedEntity.getClass == entity.getClass)
      // But they are not the same!
      assert(clonedEntity ne entity)
      // Nothing leaked over to an unrelated manager.
      intercept[java.util.NoSuchElementException] {
        m2o.entity(entity.gUID)
      }
    }

    assert(m1c.vertexSet("my/favorite/vertices/first").gUID == firstVertices.gUID)
  }

  test("No operation should be calculated twice") {
    val manager = cleanMetaManager
    val instance1 = manager.apply(new CreateSomeGraph())
    val instance2 = manager.apply(new CreateSomeGraph())
    assert(instance1 eq instance2)
  }

  test("JSON version migration") {
    val dir = cleanMetaManagerDir
    val template = new File(getClass.getResource("/graph_api/MetaGraphManagerTest/migration-test").toURI)
    FileUtils.copyDirectory(template, new File(dir))
    assert(new File(dir, "1").exists)
    assert(!new File(dir, "2").exists)
    import play.api.libs.json
    val m = VersioningMetaGraphManager(dir, new JsonMigration {
      override val version = Map(
        "com.lynxanalytics.biggraph.graph_api.CreateSomeGraph" -> 3).withDefaultValue(0)
      override val upgraders = Map[(String, Int), Function[json.JsObject, json.JsObject]](
        "com.lynxanalytics.biggraph.graph_api.CreateSomeGraph" -> 1 -> {
          j => json.JsObject(j.fields ++ json.Json.obj("arg" -> "migrated").fields)
        },
        "com.lynxanalytics.biggraph.graph_api.CreateSomeGraph" -> 2 -> {
          j => json.JsObject(j.fields.filter(_._1 != "unnecessary"))
        })
    })
    assert(new File(dir, "2").exists)
    assert(new File(dir, "2/version").exists)
    assert(m.vertexSet("one").toString ==
      "vertices of (CreateSomeGraph of arg=migrated)")
    assert(m.edgeBundle("two").toString ==
      "links of (FromVertexAttr of inputAttr=(vattr of (CreateSomeGraph of arg=migrated)))")
  }
}

private object CreateSomeGraph extends OpFromJson {
  class Input extends MagicInputSignature {
  }
  class Output(implicit instance: MetaGraphOperationInstance,
               inputs: Input) extends MagicOutput(instance) {
    val (vertices, edges) = graph
    val vattr = vertexAttribute[Long](vertices)
    val eattr = edgeAttribute[String](edges)
  }
  def fromJson(j: JsValue) = {
    assert(!j.as[play.api.libs.json.JsObject].fields.contains("unnecessary")) // For version migration testing.
    CreateSomeGraph((j \ "arg").as[String])
  }
}
private case class CreateSomeGraph(arg: String = "not set")
    extends TypedMetaGraphOp[CreateSomeGraph.Input, CreateSomeGraph.Output] {
  import CreateSomeGraph._

  @transient override lazy val inputs = new Input()

  def outputMeta(instance: MetaGraphOperationInstance) =
    new Output()(instance, inputs)

  override def toJson = Json.obj("arg" -> arg)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = ???
}

private object FromVertexAttr extends OpFromJson {
  class Input extends MagicInputSignature {
    val inputVertices = vertexSet
    val inputAttr = vertexAttribute[Long](inputVertices)
  }
  class Output(implicit instance: MetaGraphOperationInstance,
               inputs: Input) extends MagicOutput(instance) {
    val attrValues = vertexSet
    val links = edgeBundle(attrValues, inputs.inputVertices.entity)
  }
  def fromJson(j: play.api.libs.json.JsValue) = FromVertexAttr()
}
private case class FromVertexAttr()
    extends TypedMetaGraphOp[FromVertexAttr.Input, FromVertexAttr.Output] {
  import FromVertexAttr._

  @transient override lazy val inputs = new Input()

  def outputMeta(instance: MetaGraphOperationInstance) =
    new Output()(instance, inputs)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = ???
}
