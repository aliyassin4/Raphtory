package com.raphtory.internals.storage.pojograph.entities.external

import com.raphtory.api.analysis.visitor.HistoricEvent
import com.raphtory.api.analysis.visitor.InterlayerEdge
import com.raphtory.api.analysis.visitor.ReducedVertex
import com.raphtory.api.analysis.visitor.Vertex
import com.raphtory.api.analysis.visitor.PropertyMergeStrategy.PropertyMerge
import com.raphtory.internals.components.querymanager.GenericVertexMessage
import com.raphtory.internals.storage.pojograph.PojoGraphLens
import com.raphtory.internals.storage.pojograph.entities.internal.PojoVertex

import scala.language.existentials
import scala.collection.mutable
import scala.math.Ordering
import scala.reflect.ClassTag

private[raphtory] class PojoExVertex(
    private val v: PojoVertex,
    override protected val internalIncomingEdges: mutable.Map[Long, PojoExEdge],
    override protected val internalOutgoingEdges: mutable.Map[Long, PojoExEdge],
    override protected val lens: PojoGraphLens
) extends PojoExEntity(v, lens)
        with PojoVertexBase
        with ReducedVertex {

  override type Edge = PojoExEdge
  implicit override val IDOrdering: Ordering[Long] = Ordering.Long

  override def ID: Long = v.vertexId

  val exploded               = mutable.Map.empty[Long, PojoExplodedVertex]
  var explodedVertices       = Array.empty[PojoExplodedVertex]
  var explodedNeedsFiltering = false
  var interlayerEdges        = Seq.empty[InterlayerEdge]

  def explode(
      interlayerEdgeBuilder: Option[Vertex => Seq[InterlayerEdge]]
  ): Unit = {
    if (exploded.isEmpty) {
      // exploding the view
      history().foreach {
        case HistoricEvent(time, event) =>
          if (event)
            exploded += (time -> new PojoExplodedVertex(this, time, lens))
      }
      explodedVertices = exploded.values.toArray[PojoExplodedVertex]

      explodeInEdges().foreach { edge =>
        exploded(edge.timestamp).internalIncomingEdges += (
                edge.src,
                edge.timestamp
        ) -> new PojoExMultilayerEdge(
                timestamp = edge.timestamp,
                ID = (edge.src, edge.timestamp),
                src = (edge.src, edge.timestamp),
                dst = (edge.dst, edge.timestamp),
                edge = edge,
                view = lens
        )
      }
      explodeOutEdges().foreach { edge =>
        exploded(edge.timestamp).internalOutgoingEdges += (
                edge.dst,
                edge.timestamp
        ) -> new PojoExMultilayerEdge(
                timestamp = edge.timestamp,
                ID = (edge.dst, edge.timestamp),
                src = (edge.src, edge.timestamp),
                dst = (edge.dst, edge.timestamp),
                edge = edge,
                view = lens
        )
      }
    }
    //    handle interlayer edges if provided
    interlayerEdgeBuilder.foreach { builder =>
      if (interlayerEdges.nonEmpty)
        interlayerEdges.foreach { edge =>
          exploded(edge.srcTime).internalOutgoingEdges -= ((ID, edge.dstTime))
          exploded(edge.dstTime).internalIncomingEdges -= ((ID, edge.srcTime))
        }
      interlayerEdges = builder(this)
      interlayerEdges.foreach { edge =>
        val srcID = (ID, edge.srcTime)
        val dstID = (ID, edge.dstTime)
        exploded(edge.srcTime).internalOutgoingEdges += dstID -> new PojoExMultilayerEdge(
                edge.dstTime,
                dstID,
                srcID,
                dstID,
                edge,
                lens
        )
        exploded(edge.dstTime).internalIncomingEdges += srcID -> new PojoExMultilayerEdge(
                edge.srcTime,
                srcID,
                srcID,
                dstID,
                edge,
                lens
        )
      }
    }
  }

  def reduce(
      defaultMergeStrategy: Option[PropertyMerge[_, _]],
      mergeStrategyMap: Option[Map[String, PropertyMerge[_, _]]],
      aggregate: Boolean
  ): Unit = {
    if (defaultMergeStrategy.nonEmpty || mergeStrategyMap.nonEmpty) {
      val states   = mutable.Map.empty[String, mutable.ArrayBuffer[(Long, Any)]]
      val collect  = defaultMergeStrategy match {
        case Some(_) => (_: String) => true
        case None    =>
          val strategyMap = mergeStrategyMap.get
          (key: String) => strategyMap contains key
      }
      exploded.values.foreach { vertex =>
        vertex.computationValues.foreach {
          case (key, value) =>
            if (collect(key))
              states.getOrElseUpdate(
                      key,
                      mutable.ArrayBuffer.empty[(Long, Any)]
              ) += ((vertex.timestamp, value))
        }
      }
      val strategy = defaultMergeStrategy match {
        case Some(strategy) =>
          mergeStrategyMap match {
            case Some(strategyMap) => (key: String) => strategyMap.getOrElse(key, strategy)
            case None              => (_: String) => strategy
          }
        case None           =>
          val strategyMap = mergeStrategyMap.get
          (key: String) => strategyMap(key)
      }

      states.foreach {
        case (key, history) =>
          val strat = strategy(key)
          strat match {
            case v: PropertyMerge[Any, Any] => setState(key, v(history.toSeq))
          }
      }
    }
    if (aggregate) {
      exploded.clear()
      interlayerEdges = Seq()
    }
  }

  def filterExplodedVertices(): Unit =
    if (explodedNeedsFiltering) {
      explodedVertices.filterNot(_.isFiltered)
      explodedNeedsFiltering = false
    }

  private var computationValues: Map[String, Any] =
    Map.empty //Partial results kept between supersteps in calculation

  // state related
  def setState(key: String, value: Any): Unit =
    computationValues += ((key, value))

  def getState[T](key: String, includeProperties: Boolean = false): T =
    if (computationValues.contains(key))
      computationValues(key).asInstanceOf[T]
    else if (includeProperties && v.properties.contains(key))
      getProperty[T](key).get
    else if (includeProperties)
      throw new Exception(
              s"$key not found within analytical state or properties for vertex ${v.vertexId}"
      )
    else
      throw new Exception(s"$key not found within analytical state for vertex ${v.vertexId}")

  def getStateOrElse[T](key: String, value: T, includeProperties: Boolean = false): T =
    if (computationValues contains key)
      computationValues(key).asInstanceOf[T]
    else if (includeProperties && v.properties.contains(key))
      getProperty[T](key).get
    else
      value

  def containsState(key: String, includeProperties: Boolean = false): Boolean =
    computationValues.contains(key) || (includeProperties && v.properties.contains(key))

  def getOrSetState[T](key: String, value: T, includeProperties: Boolean = false): T = {
    var output_value = value
    if (containsState(key))
      output_value = getState[T](key)
    else {
      if (includeProperties && v.properties.contains(key))
        output_value = getProperty[T](key).get
      setState(key, output_value)
    }
    output_value
  }

  def appendToState[T: ClassTag](key: String, value: T): Unit = //write function later
    computationValues.get(key) match {
      case Some(arr) =>
        setState(key, arr.asInstanceOf[Array[T]] :+ value)
      case None      =>
        setState(key, Array(value))
    }

  // implement receive in case of exploded view (normal receive is handled in PojoVertexBase)
  override def receiveMessage(msg: GenericVertexMessage[_]): Unit =
    msg.vertexId match {
      case _: Long               =>
        super.receiveMessage(msg)
      case (_: Long, time: Long) =>
        exploded(time)
          .receiveMessage(msg)
    }

  override def individualEdge(
      edges: mutable.Map[Long, PojoExEdge],
      after: Long,
      before: Long,
      id: Long
  ): Option[PojoExEdge] =
    super.individualEdge(edges, after, before, id).map(edge => edge.viewBetween(after, before))

  override def allEdge(
      edges: mutable.Map[Long, PojoExEdge],
      after: Long,
      before: Long
  ): List[PojoExEdge] =
    super.allEdge(edges, after, before).map(edge => edge.viewBetween(after, before))
}
