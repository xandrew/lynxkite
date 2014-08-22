package com.lynxanalytics.biggraph.controllers

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.graph_api.Scripting._
import com.lynxanalytics.biggraph.graph_operations
import scala.reflect.runtime.universe._

class Project(val projectName: String)(implicit manager: MetaGraphManager) {
  val path: SymbolPath = s"projects/$projectName"
  def toFE(implicit dm: DataManager): FEProject = {
    val vs = Option(vertexSet).map(_.gUID.toString).getOrElse("")
    val eb = Option(edgeBundle).map(_.gUID.toString).getOrElse("")
    // For now, counts are calculated here. TODO: Make them respond to soft filters.
    val vsCount = if (vertexSet == null) 0 else {
      val op = graph_operations.CountVertices()
      op(op.vertices, vertexSet).result.count.value
    }
    val esCount = if (edgeBundle == null) 0 else {
      val op = graph_operations.CountEdges()
      op(op.edges, edgeBundle).result.count.value
    }
    val undo = if (checkpointCount > 0) (checkpointCount - 1).toString else ""
    val redo = if (undoCount > 0) (checkpointCount + 1).toString else ""
    FEProject(
      projectName, undo, redo, vs, eb,
      vsCount, esCount, notes,
      scalars.map { case (name, scalar) => UIValue(scalar.gUID.toString, name) }.toSeq,
      vertexAttributes.map { case (name, attr) => UIValue(attr.gUID.toString, name) }.toSeq,
      edgeAttributes.map { case (name, attr) => UIValue(attr.gUID.toString, name) }.toSeq,
      segmentations.map(_.toFE),
      opCategories = Seq())
  }

  private def checkpointCount = get("checkpointCount") match {
    case "" => 0
    case x => x.toInt
  }
  private def checkpointCount_=(x: Int): Unit = set("checkpointCount", x.toString)
  private def undoCount = get("undoCount") match {
    case "" => 0
    case x => x.toInt
  }
  private def undoCount_=(x: Int): Unit = set("undoCount", x.toString)

  def checkpoint(): Unit = {
    cp(path, s"checkpoints/$path/$checkpointCount")
    undoCount = 0
    checkpointCount += 1
  }
  def undo(checkpointIndex: Int): Unit = {
    val u = undoCount
    val c = checkpointCount
    if (u == 0) {
      checkpoint() // Save state on first undo.
    }
    assert(checkpointIndex == c - 1, "$checkpointIndex != $c - 1")
    cp(s"checkpoints/$path/$checkpointIndex", path)
    undoCount = u + 1
    checkpointCount = c - 1
  }
  def redo(checkpointIndex: Int): Unit = {
    val u = undoCount
    val c = checkpointCount
    assert(u > 0, s"undoCount is $undoCount")
    assert(checkpointIndex == c + 1, "$checkpointIndex != $c + 1")
    cp(s"checkpoints/$path/$checkpointIndex", path)
    undoCount = u - 1
    checkpointCount = c + 1
  }

  def isSegmentation = {
    val grandFather = path.parent.parent
    grandFather.nonEmpty && (grandFather.name == 'segmentations)
  }
  def asSegmentation = {
    assert(isSegmentation, s"$projectName is not a segmentation")
    // If our parent is a top-level project, path is like:
    //   project/parentName/segmentations/segmentationName/project
    val parentName = new SymbolPath(path.drop(1).dropRight(3))
    val segmentationName = path.dropRight(1).last.name
    Segmentation(parentName.toString, segmentationName)
  }

  def notes(implicit dm: DataManager) = manager.scalarOf[String](path / "notes").value
  def notes_=(n: String) = {
    set("notes", graph_operations.CreateStringScalar(n)().result.created)
  }

  def vertexSet = existing(path / "vertexSet").map(manager.vertexSet(_)).getOrElse(null)
  def vertexSet_=(e: VertexSet) = {
    if (e != vertexSet) {
      // TODO: "Induce" the edges and attributes to the new vertex set.
      edgeBundle = null
      vertexAttributes = Map()
    }
    set("vertexSet", e)
    if (e != null) {
      val op = graph_operations.CountVertices()
      scalars("vertex count") = op(op.vertices, vertexSet).result.count
    }
  }

  def pullBackWithInjection(injection: EdgeBundle): Unit = {
    assert(injection.properties.compliesWith(EdgeBundleProperties.injection))
    assert(injection.dstVertexSet.gUID == vertexSet.gUID)
    val origVS = vertexSet
    val origVAttrs = vertexAttributes.toIndexedSeq
    val origEB = edgeBundle
    val origEAttrs = edgeAttributes.toIndexedSeq
    vertexSet = injection.srcVertexSet
    origVAttrs.foreach {
      case (name, attr) =>
        vertexAttributes(name) =
          graph_operations.PulledOverVertexAttribute.pullAttributeVia(attr, injection)
    }

    if (origEB != null) {
      val iop = graph_operations.InducedEdgeBundle()
      edgeBundle = iop(iop.srcInjection, injection)(iop.dstInjection, injection)(iop.edges, origEB)
        .result.induced
    }

    origEAttrs.foreach {
      case (name, attr) =>
        edgeAttributes(name) =
          graph_operations.PulledOverEdgeAttribute.pullAttributeTo(attr, edgeBundle)
    }

    segmentations.foreach { seg =>
      val op = graph_operations.InducedEdgeBundle(induceDst = false)
      seg.belongsTo = op(op.srcInjection, injection)(op.edges, seg.belongsTo).result.induced
    }

    if (isSegmentation) {
      val seg = asSegmentation
      val op = graph_operations.InducedEdgeBundle(induceSrc = false)
      seg.belongsTo = op(op.dstInjection, injection)(op.edges, seg.belongsTo).result.induced
    }
  }

  def edgeBundle = existing(path / "edgeBundle").map(manager.edgeBundle(_)).getOrElse(null)
  def edgeBundle_=(e: EdgeBundle) = {
    if (e != edgeBundle) {
      assert(e == null || vertexSet != null, s"No vertex set for project $projectName")
      assert(e == null || e.srcVertexSet == vertexSet, s"Edge bundle does not match vertex set for project $projectName")
      assert(e == null || e.dstVertexSet == vertexSet, s"Edge bundle does not match vertex set for project $projectName")
      // TODO: "Induce" the attributes to the new edge bundle.
      edgeAttributes = Map()
    }
    set("edgeBundle", e)
  }

  def scalars = new ScalarHolder
  def scalars_=(scalars: Map[String, Scalar[_]]) = {
    existing(path / "scalars").foreach(manager.rmTag(_))
    for ((name, scalar) <- scalars) {
      manager.setTag(path / "scalars" / name, scalar)
    }
  }
  def scalarNames[T: TypeTag] = scalars.collect {
    case (name, scalar) if typeOf[T] =:= typeOf[Nothing] || scalar.is[T] => name
  }.toSeq

  def vertexAttributes = new VertexAttributeHolder
  def vertexAttributes_=(attrs: Map[String, VertexAttribute[_]]) = {
    existing(path / "vertexAttributes").foreach(manager.rmTag(_))
    assert(attrs.isEmpty || vertexSet != null, s"No vertex set for project $projectName")
    for ((name, attr) <- attrs) {
      assert(attr.vertexSet == vertexSet, s"Vertex attribute $name does not match vertex set for project $projectName")
      manager.setTag(path / "vertexAttributes" / name, attr)
    }
  }
  def vertexAttributeNames[T: TypeTag] = vertexAttributes.collect {
    case (name, attr) if typeOf[T] =:= typeOf[Nothing] || attr.is[T] => name
  }.toSeq

  def edgeAttributes = new EdgeAttributeHolder
  def edgeAttributes_=(attrs: Map[String, EdgeAttribute[_]]) = {
    existing(path / "edgeAttributes").foreach(manager.rmTag(_))
    assert(attrs.isEmpty || edgeBundle != null, s"No edge bundle for project $projectName")
    for ((name, attr) <- attrs) {
      assert(attr.edgeBundle == edgeBundle, s"Edge attribute $name does not match edge bundle for project $projectName")
      manager.setTag(path / "edgeAttributes" / name, attr)
    }
  }
  def edgeAttributeNames[T: TypeTag] = edgeAttributes.collect {
    case (name, attr) if typeOf[T] =:= typeOf[Nothing] || attr.is[T] => name
  }.toSeq

  def segmentations = segmentationNames.map(segmentation(_))
  def segmentation(name: String) = Segmentation(projectName, name)
  def segmentationNames = ls("segmentations").map(_.last.name)

  def copy(to: Project): Unit = cp(path, to.path)

  private def cp(from: SymbolPath, to: SymbolPath) = {
    existing(to).foreach(manager.rmTag(_))
    manager.cpTag(from, to)
  }

  private def existing(tag: SymbolPath): Option[SymbolPath] =
    if (manager.tagExists(tag)) Some(tag) else None
  private def set(tag: String, entity: MetaGraphEntity): Unit = {
    if (entity == null) {
      existing(path / tag).foreach(manager.rmTag(_))
    } else {
      manager.setTag(path / tag, entity)
    }
  }
  private def set(tag: String, content: String): Unit = manager.setTag(path / tag, content)
  private def get(tag: String): String = existing(path / tag).map(manager.getTag(_)).getOrElse("")
  private def ls(dir: String) = if (manager.tagExists(path / dir)) manager.lsTag(path / dir) else Nil

  abstract class Holder[T <: MetaGraphEntity](dir: String) extends Iterable[(String, T)] {
    def validate(name: String, entity: T): Unit
    def update(name: String, entity: T) = {
      validate(name, entity)
      manager.setTag(path / dir / name, entity)
    }
    def apply(name: String) =
      manager.entity(path / dir / name).asInstanceOf[T]
    def iterator =
      ls(dir).map(_.last.name).map(p => p -> apply(p)).iterator
  }
  class ScalarHolder extends Holder[Scalar[_]]("scalars") {
    def validate(name: String, scalar: Scalar[_]) = {}
  }
  class VertexAttributeHolder extends Holder[VertexAttribute[_]]("vertexAttributes") {
    def validate(name: String, attr: VertexAttribute[_]) =
      assert(attr.vertexSet == vertexSet, s"Vertex attribute $name does not match vertex set for project $projectName")
  }
  class EdgeAttributeHolder extends Holder[EdgeAttribute[_]]("edgeAttributes") {
    def validate(name: String, attr: EdgeAttribute[_]) =
      assert(attr.edgeBundle == edgeBundle, s"Edge attribute $name does not match edge bundle for project $projectName")
  }
}

object Project {
  def apply(projectName: String)(implicit metaManager: MetaGraphManager): Project = new Project(projectName)
}

case class Segmentation(parentName: String, name: String)(implicit manager: MetaGraphManager) {
  def parent = Project(parentName)
  val path: SymbolPath = s"projects/$parentName/segmentations/$name"
  def toFE(implicit dm: DataManager) =
    FESegmentation(name, project.projectName, UIValue.fromEntity(belongsTo))
  def belongsTo = manager.edgeBundle(path / "belongsTo")
  def belongsTo_=(eb: EdgeBundle) = {
    assert(eb.dstVertexSet == project.vertexSet, s"Incorrect 'belongsTo' relationship for $name")
    manager.setTag(path / "belongsTo", eb)
  }
  def project = Project(s"$parentName/segmentations/$name/project")
}
