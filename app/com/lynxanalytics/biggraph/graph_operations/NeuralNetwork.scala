// Trains a neural network on a graph and uses it to predict an attribute.
package com.lynxanalytics.biggraph.graph_operations

import breeze.linalg._
import breeze.stats.distributions.{ RandBasis, ThreadLocalRandomGenerator }

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.neural
import com.lynxanalytics.biggraph.spark_util.SortedRDD
import com.lynxanalytics.biggraph.spark_util.Implicits._
import com.lynxanalytics.biggraph.{ bigGraphLogger => log }

import org.apache.commons.math3.random.MersenneTwister

object NeuralNetwork extends OpFromJson {
  class Input(featureCount: Int) extends MagicInputSignature {
    val vertices = vertexSet
    val edges = edgeBundle(vertices, vertices)
    val label = vertexAttribute[Double](vertices)
    val features = (0 until featureCount).map(
      i => vertexAttribute[Double](vertices, Symbol("feature-" + i)))
  }
  class Output(implicit instance: MetaGraphOperationInstance,
               inputs: Input) extends MagicOutput(instance) {
    val prediction = vertexAttribute[Double](inputs.vertices.entity)
  }
  def fromJson(j: JsValue) = NeuralNetwork(
    (j \ "featureCount").as[Int],
    (j \ "networkSize").as[Int],
    (j \ "learningRate").as[Double],
    (j \ "radius").as[Int],
    (j \ "hideState").as[Boolean],
    (j \ "forgetFraction").as[Double],
    (j \ "trainingRadius").as[Int],
    (j \ "maxTrainingVertices").as[Int],
    (j \ "minTrainingVertices").as[Int],
    (j \ "iterationsInTraining").as[Int],
    (j \ "subgraphsInTraining").as[Int],
    (j \ "numberOfTrainings").as[Int],
    (j \ "knownLabelWeight").as[Double],
    (j \ "seed").as[Int])
}
import NeuralNetwork._
case class NeuralNetwork(
    featureCount: Int,
    networkSize: Int,
    learningRate: Double,
    radius: Int,
    hideState: Boolean,
    forgetFraction: Double,
    trainingRadius: Int,
    maxTrainingVertices: Int,
    minTrainingVertices: Int,
    iterationsInTraining: Int,
    subgraphsInTraining: Int,
    numberOfTrainings: Int,
    knownLabelWeight: Double,
    seed: Int) extends TypedMetaGraphOp[Input, Output] {
  @transient override lazy val inputs = new Input(featureCount)
  def outputMeta(instance: MetaGraphOperationInstance) = new Output()(instance, inputs)
  override def toJson = Json.obj(
    "featureCount" -> featureCount,
    "networkSize" -> networkSize,
    "learningRate" -> learningRate,
    "radius" -> radius,
    "hideState" -> hideState,
    "forgetFraction" -> forgetFraction,
    "trainingRadius" -> trainingRadius,
    "maxTrainingVertices" -> maxTrainingVertices,
    "minTrainingVertices" -> minTrainingVertices,
    "iterationsInTraining" -> iterationsInTraining,
    "subgraphsInTraining" -> subgraphsInTraining,
    "numberOfTrainings" -> numberOfTrainings,
    "knownLabelWeight" -> knownLabelWeight,
    "seed" -> seed)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {

    implicit val id = inputDatas
    val isolatedVertices: Map[ID, Seq[ID]] = inputs.vertices.rdd.keys.collect.map(id => id -> Seq()).toMap
    val edges: Seq[Edge] = inputs.edges.rdd.values.collect
    val edgeLists: Map[ID, Seq[ID]] = isolatedVertices ++ edges.groupBy(_.src).mapValues(_.map(_.dst))
    val features = {
      val arrays = inputs.vertices.rdd.mapValues(_ => new Array[Double](featureCount))
      inputs.features.zipWithIndex.foldLeft(arrays) {
        case (arrays, (feature, idx)) =>
          arrays.sortedJoin(feature.rdd).mapValues {
            case (array, feature) =>
              array(idx) = feature
              array
          }
      }
    }
    val random = new util.Random(0)
    val labelOpt = inputs.vertices.rdd.sortedLeftOuterJoin(inputs.label.rdd).mapValues(_._2)
    val data: SortedRDD[ID, (Option[Double], Array[Double])] = labelOpt.sortedJoin(features)
    implicit val randBasis =
      new RandBasis(new ThreadLocalRandomGenerator(new MersenneTwister(seed)))

    val initialNetwork = {
      import neural.Gates._
      val vs = Neighbors()
      val eb = V("edge bias")
      val input = Sum(vs * M("edge matrix")) + eb
      val state = Input("state")
      val update = Sigmoid(input * M("update i") + state * M("update h"))
      val reset = Sigmoid(input * M("reset i") + state * M("reset h"))
      val tilde = Tanh(input * M("activation i") + state * reset * M("activation h"))
      neural.Network(
        size = networkSize,
        "new state" -> (state - update * state + update * tilde)
      )
    }
    val network = (1 to numberOfTrainings).foldLeft(initialNetwork) {
      (previous, current) =>
        averageNetworks((1 to subgraphsInTraining).map { i =>
          val (trainingVertices, trainingEdgeLists, trainingData) =
            selectRandomSubgraph(data.collect.iterator, edgeLists, trainingRadius,
              maxTrainingVertices, minTrainingVertices, random.nextInt)
          val (net, dataForGradientCheck) = train(trainingVertices, trainingEdgeLists, trainingData,
            previous, iterationsInTraining)
          if (!gradientCheck(trainingVertices, trainingEdgeLists, previous, dataForGradientCheck)) {
            println("Gradient check failed.")
          }
          net
        })
    }
    val prediction = predict(data.collect.iterator, edgeLists, network).toSeq
    output(o.prediction,
      rc.sparkContext.parallelize(prediction).sortUnique(inputs.vertices.rdd.partitioner.get))
  }

  def averageNetworks(networks: Seq[neural.Network]) =
    networks.reduce(_ + _) / networks.size

  def selectRandomSubgraph(
    dataIterator: Iterator[(ID, (Option[Double], Array[Double]))],
    edgeLists: Map[ID, Seq[ID]],
    selectionRadius: Int,
    maxNumberOfVertices: Int,
    minNumberOfVertices: Int,
    seed: Int): (Seq[ID], Map[ID, Seq[ID]], Seq[(ID, (Option[Double], Array[Double]))]) = {
    val data = dataIterator.toSeq
    val vertices = data.map(_._1)
    if (selectionRadius < 0) { // Return the whole graph, for testing.
      (vertices, edgeLists, data)
    } else {
      val random = new util.Random(seed)
      def verticesAround(vertex: ID): Seq[ID] = (0 until selectionRadius).foldLeft(Seq(vertex)) {
        (previous, current) => previous.flatMap(id => id +: edgeLists(id)).distinct
      }
      var subsetOfVertices: Seq[ID] = Seq()
      while (subsetOfVertices.size < minNumberOfVertices) {
        val baseVertex = vertices(random.nextInt(vertices.size))
        subsetOfVertices = subsetOfVertices ++ verticesAround(baseVertex)
      }
      subsetOfVertices = subsetOfVertices.take(maxNumberOfVertices)
      val subsetOfEdges = subsetOfVertices.map {
        id => id -> edgeLists(id).filter(subsetOfVertices.contains(_))
      }.toMap
      val subsetOfData = data.filter(vertex => subsetOfVertices.contains(vertex._1))
      (subsetOfVertices, subsetOfEdges, subsetOfData)
    }
  }

  def getTrueState(
    data: Seq[(ID, (Option[Double], Array[Double]))]): neural.GraphData = {
    val labels = data.flatMap { case (id, (labelOpt, features)) => labelOpt.map(id -> _) }.toMap
    val features = data.map { case (id, (labelOpt, features)) => id -> features }.toMap
    val vertices = data.map(_._1)
    // True state contains label and features.
    vertices.map { id =>
      val state = DenseVector.zeros[Double](networkSize)
      if (labels.contains(id)) {
        state(0) = labels(id) // Label is in position 0.
        state(1) = 1.0 // Mark of a source of truth is in position 1.
      }
      val fs = features(id)
      for (i <- 0 until fs.size) {
        state(i + 2) = fs(i) // Features start from position 2.
      }
      id -> state
    }.toMap
  }

  // Forgets the label.
  def blanked(state: neural.DoubleVector) = {
    val s = state.copy
    s(0) = 0.0
    s(1) = 0.0
    s
  }

  def forwardPass(
    vertices: Seq[ID],
    edges: Map[ID, Seq[ID]],
    neighborsState: neural.GraphData,
    ownState: neural.GraphData,
    network: neural.Network): Seq[neural.GateValues] = {
    (1 until radius).scanLeft {
      network.forward(vertices, edges, neighborsState, "state" -> ownState)
    } { (previous, r) =>
      network.forward(vertices, edges, previous("new state"), "state" -> previous("new state"))
    }
  }

  case class DataForGradientCheck(
    weights: List[Map[String, neural.DoubleMatrix]],
    gradients: List[Map[String, neural.DoubleMatrix]],
    trueState: neural.GraphData,
    initialStates: List[neural.GraphData])

  def train(
    vertices: Seq[ID],
    edges: Map[ID, Seq[ID]],
    data: Seq[(ID, (Option[Double], Array[Double]))],
    startingNetwork: neural.Network,
    iterations: Int): (neural.Network, DataForGradientCheck) = {
    assert(networkSize >= featureCount + 2, s"Network size must be at least ${featureCount + 2}.")
    var network = startingNetwork
    val weightsForGradientCheck = new scala.collection.mutable.ListBuffer[Map[String, neural.DoubleMatrix]]
    val gradientsForGradientCheck = new scala.collection.mutable.ListBuffer[Map[String, neural.DoubleMatrix]]
    val trueState = getTrueState(data)
    val initialStates = scala.collection.mutable.ListBuffer[neural.GraphData]()
    for (i <- 1 to iterations) {
      val random = new util.Random(1)
      val keptState = trueState.map {
        case (id, state) =>
          if (random.nextDouble < forgetFraction) id -> blanked(state)
          else id -> state
      }
      val initialState = if (!hideState) keptState else keptState.map {
        // In "hideState" mode neighbors can see the labels but it is hidden from the node itself.
        case (id, state) => id -> blanked(state)
      }
      initialStates += initialState
      val outputs = forwardPass(vertices, edges, keptState, initialState, network)
      weightsForGradientCheck += network.allWeights.toMap
      val finalOutputs = outputs.last("new state")

      // Backward pass.
      var numberOfForgotten = 0.0
      var numberOfKnown = 0.0
      val errors: Map[ID, Double] = data.map {
        case (id, (Some(label), features)) if (keptState(id)(1) == 0 || forgetFraction == 0.0) =>
          numberOfForgotten += 1
          // The label is predicted in position 0.
          id -> (finalOutputs(id)(0) - label)
        case (id, (Some(label), features)) =>
          numberOfKnown += 1
          id -> (finalOutputs(id)(0) - label) * knownLabelWeight
        case (id, (None, features)) =>
          id -> 0.0
      }.toMap
      val correctionRatio = {
        if (forgetFraction != 0.0) {
          (numberOfKnown + numberOfForgotten) / (numberOfKnown * knownLabelWeight + numberOfForgotten)
        } else 1
      }
      val errorTotal = errors.values.map(e => e * e).sum
      log.info(s"Total error in iteration $i: $errorTotal")
      val finalGradient: neural.GraphData = errors.map {
        case (id, error) =>
          val vec = DenseVector.zeros[Double](networkSize)
          vec(0) = 2 * error * correctionRatio
          id -> vec
      }
      val gradients = outputs.init.scanRight {
        network.backward(vertices, edges, outputs.last, "new state" -> finalGradient)
      } { (outputs, next) =>
        import neural.Implicits._
        network.backward(vertices, edges, outputs, "new state" -> (next("state") + next.neighbors))
      }
      val updated = network.update(gradients, learningRate)
      network = updated._1
      gradientsForGradientCheck += updated._2
    }
    (network, DataForGradientCheck(weightsForGradientCheck.toList, gradientsForGradientCheck.toList, trueState, initialStates.toList))
  }

  def gradientCheck(
    vertices: Seq[ID],
    edges: Map[ID, Seq[ID]],
    initialNetwork: neural.Network,
    data: DataForGradientCheck): Boolean = {
    val epsilon = 1e-5
    val threshold = 1e-2
    implicit val randBasis = new RandBasis(new ThreadLocalRandomGenerator(new MersenneTwister(0)))
    val trueState = data.trueState
    val initialState = data.initialStates(0) //now the gradient check is only implemented for hiding mode, so initialState is the same in all iterations.
    val weights = data.weights
    val gradients = data.gradients
    //Approximate the derivatives.
    val approximatedGradients = weights.map { w =>
      w.flatMap {
        case (name, values) =>
          val rows = values.rows
          val cols = values.cols
          for (row <- 0 until rows; col <- 0 until cols) yield {
            val epsilonMatrix = DenseMatrix.zeros[Double](rows, cols)
            epsilonMatrix(row, col) = epsilon
            //Increase weigth and predict with it.
            val partialIncreasedWeights = w + (name -> (w(name) + epsilonMatrix))
            val outputsWithIncreased = forwardPass(
              vertices, edges, trueState, initialState,
              initialNetwork.copy(weights = partialIncreasedWeights))
            val errorsWithIncreased = outputsWithIncreased.last("new state").map {
              case (id, state) =>
                id -> (state(0) - trueState(id)(0))
            }
            val errorTotalWithIncreased = errorsWithIncreased.values.map(e => e * e).sum
            //Decrease weight and predict with it.
            val partialDecreasedWeights = w + (name -> (w(name) - epsilonMatrix))
            val outputsWithDecreased = forwardPass(
              vertices, edges, trueState, initialState,
              initialNetwork.copy(weights = partialDecreasedWeights))
            val errorsWithDecreased = outputsWithDecreased.last("new state").map {
              case (id, state) =>
                id -> (state(0) - trueState(id)(0))
            }
            val errorTotalWithDecreased = errorsWithDecreased.values.map(e => e * e).sum
            val gradient = (errorTotalWithIncreased - errorTotalWithDecreased) / (2 * epsilon)
            (s"$name $row $col", gradient)
          }
      }.toMap
    }
    // Gradients calculated with backpropagation.
    val backPropGradients = gradients.map { g =>
      g.flatMap {
        case (name, values) =>
          val rows = values.rows
          val cols = values.cols
          for (row <- 0 until rows; col <- 0 until cols) yield (s"$name $row $col", values(row, col))
      }.toMap
    }

    var gradientsOK = true
    val relativeErrors = (0 until approximatedGradients.length).map { i =>
      approximatedGradients(i).map {
        case (name, value) =>
          val otherValue = backPropGradients(i)(name)
          val relativeError = {
            if (value != otherValue) {
              math.abs(value - otherValue) / math.max(math.abs(value), math.abs(otherValue))
            } else 0
          }
          if (relativeError > threshold) gradientsOK = false
          (name, relativeError)
      }.toMap
    }
    println(relativeErrors)
    gradientsOK
  }

  def predict(
    dataIterator: Iterator[(ID, (Option[Double], Array[Double]))],
    edges: Map[ID, Seq[ID]],
    network: neural.Network): Iterator[(ID, Double)] = {
    val data = dataIterator.toSeq
    val vertices = data.map(_._1)

    val trueState = getTrueState(data)
    val initialState = if (!hideState) trueState else trueState.map {
      // In "hideState" mode neighbors can see the labels but it is hidden from the node itself.
      case (id, state) => id -> blanked(state)
    }

    val outputs = forwardPass(vertices, edges, trueState, initialState, network)
    outputs.last("new state").map {
      case (id, state) => id -> state(0)
    }.iterator
  }
}
