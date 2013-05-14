package com.github.mdr.ascii.layout

import com.github.mdr.ascii.layout.drawing._
import com.github.mdr.ascii.layout.layering._
import com.github.mdr.ascii.parser.Dimension
import com.github.mdr.ascii.parser.Point
import com.github.mdr.ascii.parser.Region
import com.github.mdr.ascii.parser.Translatable
import com.github.mdr.ascii.util.Utils
import com.github.mdr.ascii.util.Utils._

object Layouter {

  private val MINIMUM_VERTEX_HEIGHT = 3

}

class Layouter(vertexRenderingStrategy: VertexRenderingStrategy[_]) {

  import Layouter._

  def layout(layering: Layering): Drawing = {
    val layerInfos: Map[Layer, LayerInfo] = calculateLayerInfos(layering)

    var previousLayerInfo: LayerInfo = LayerInfo(Map())
    var incompleteEdges: Map[DummyVertex, List[Point]] = Map()
    var diagramElements: List[DrawingElement] = Nil
    for (layer ← layering.layers) {
      val LayerLayoutResult(elements, updatedLayerInfo, updatedIncompletedEdges) =
        layoutLayer(previousLayerInfo, layerInfos(layer), layering.edges, incompleteEdges)
      previousLayerInfo = updatedLayerInfo
      incompleteEdges = updatedIncompletedEdges
      diagramElements ++= elements
    }
    Drawing(diagramElements)
  }

  /**
   * Calculate layer infos, with vertices given their correct column coordinates, but awaiting
   * correct row coordinates.
   */
  private def calculateLayerInfos(layering: Layering): Map[Layer, LayerInfo] = {
    var layerInfos: Map[Layer, LayerInfo] = Map()
    for ((previousLayerOpt, currentLayer, nextLayerOpt) ← Utils.withPreviousAndNext(layering.layers)) {
      val vertexInfos = calculateLayerInfo(currentLayer, layering.edges, previousLayerOpt, nextLayerOpt)
      layerInfos += currentLayer -> vertexInfos
    }
    spaceVertices(layerInfos)
  }

  /**
   * For each vertex in the layer, calculate its size and assign ports for its in- and out-edges.
   */
  private def calculateLayerInfo(layer: Layer, edges: List[Edge], previousLayerOpt: Option[Layer], nextLayerOpt: Option[Layer]): LayerInfo = {
    val inEdges = previousLayerOpt.map { previousLayer ⇒
      edges.sortBy { case Edge(v1, _) ⇒ previousLayer.vertices.indexOf(v1) }
    }.getOrElse(Nil)
    val outEdges = nextLayerOpt.map { nextLayer ⇒
      edges.sortBy { case Edge(_, v2) ⇒ nextLayer.vertices.indexOf(v2) }
    }.getOrElse(Nil)
    def getInEdges(vertex: Vertex) = inEdges collect { case e @ Edge(v1, `vertex`) ⇒ e }
    def getOutEdges(vertex: Vertex) = outEdges collect { case e @ Edge(`vertex`, v2) ⇒ e }

    def getDimension(vertex: Vertex): Dimension = vertex match {
      case v: RealVertex  ⇒ calculateDimension(v, getInEdges(vertex).size, getOutEdges(vertex).size)
      case _: DummyVertex ⇒ Dimension(height = 1, width = 1)
    }
    val dimensions: Map[Vertex, Dimension] = makeMap(layer.vertices, getDimension)
    val regions: Map[Vertex, (Region, Region)] = calculateVertexRegions(layer, dimensions)
    def buildVertexInfo(v: Vertex) = {
      val (boxRegion, greaterRegion) = regions(v)
      makeVertexInfo(v, boxRegion, greaterRegion, getInEdges(v), getOutEdges(v))
    }
    LayerInfo(makeMap(layer.vertices, buildVertexInfo))
  }

  private def makeVertexInfo(vertex: Vertex, boxRegion: Region, greaterRegion: Region, inEdges: List[Edge], outEdges: List[Edge]): VertexInfo =
    vertex match {
      case realVertex: RealVertex ⇒
        val inDegree = inEdges.size + realVertex.selfEdges
        val outDegree = outEdges.size + realVertex.selfEdges
        val inPorts: List[Point] = portOffsets(inDegree, boxRegion.width).map(boxRegion.topLeft.right)
        val outPorts: List[Point] = portOffsets(outDegree, boxRegion.width).map(boxRegion.bottomLeft.right)
        val inEdgeToPortMap = inEdges.zip(inPorts).toMap
        val outEdgeToPortMap = outEdges.zip(outPorts).toMap
        val selfInPorts = inPorts.drop(inEdges.size)
        val selfOutPorts = outPorts.drop(outEdges.size)
        VertexInfo(boxRegion, greaterRegion, inEdgeToPortMap, outEdgeToPortMap, selfInPorts, selfOutPorts)
      case _: DummyVertex ⇒
        val List(inVertex) = inEdges
        val List(outVertex) = outEdges
        val inEdgeToPortMap = Map(inVertex -> boxRegion.topLeft)
        val outEdgeToPortMap = Map(outVertex -> boxRegion.topLeft)
        VertexInfo(boxRegion, greaterRegion, inEdgeToPortMap, outEdgeToPortMap, Nil, Nil)
    }

  /**
   * Space out edge ports even along the edge of a vertex.
   *
   * We leave room for self edges at the right
   */
  private def portOffsets(edges: List[Edge], vertexWidth: Int, selfEdges: Int): Map[Edge, Int] = {
    val factor = vertexWidth / (edges.size + selfEdges + 1)
    val centraliser = (vertexWidth - factor * (edges.size + selfEdges + 1)) / 2
    edges.zipWithIndex.map { case (v, i) ⇒ (v, (i + 1) * factor + centraliser) }.toMap
  }

  /**
   * Space out edge ports evenly along the top or bottom edge of a vertex.
   */
  private def portOffsets(portCount: Int, vertexWidth: Int): List[Int] = {
    val factor = vertexWidth / (portCount + 1)
    val centraliser = (vertexWidth - factor * (portCount + 1)) / 2
    0.until(portCount).toList.map(i ⇒ (i + 1) * factor + centraliser)
  }

  /**
   * Calculate dimension based on vertex rendering strategy together with the number of in/out edges
   */
  private def calculateDimension(v: RealVertex, inDegree: Int, outDegree: Int) = {
    val selfEdges = v.selfEdges
    val requiredInputWidth = (inDegree + selfEdges) * 2 + 3
    val requiredOutputWidth = (outDegree + selfEdges) * 2 + 3
    val Dimension(preferredHeight, preferredWidth) = getPreferredSize(vertexRenderingStrategy, v)
    val width = math.max(math.max(requiredInputWidth, requiredOutputWidth), preferredWidth + 2)
    val height = math.max(MINIMUM_VERTEX_HEIGHT, preferredHeight + 2)
    Dimension(height = height, width = width)
  }

  /**
   * Initially pack vertex regions close together, so we can determine the minimum width of the entire
   * drawing.
   *
   * @return pair of regions: first = region of the vertex box; second = "great region" which includes the space for any
   *   self edges which need to wrap around the vertex.
   */
  private def calculateVertexRegions(layer: Layer, dimensions: Map[Vertex, Dimension]): Map[Vertex, (Region, Region)] = {
    var regions: Map[Vertex, (Region, Region)] = Map()
    var pos = Point(0, 0)
    for (vertex ← layer.vertices) {
      val boxRegion = Region(pos, dimensions(vertex))
      val selfEdgesSpacing = vertex match {
        case realVertex: RealVertex if realVertex.selfEdges > 0 ⇒ realVertex.selfEdges * 2
        case _                                                  ⇒ 0
      }
      val greaterRegion = boxRegion.expandRight(selfEdgesSpacing).expandUp(selfEdgesSpacing).expandDown(selfEdgesSpacing)
      regions += vertex -> (boxRegion, greaterRegion)
      pos = boxRegion.topRight.right(1 + selfEdgesSpacing)
    }
    regions
  }

  private def calculateDiagramWidth(layerInfos: Map[Layer, LayerInfo]) = {
    def vertexWidth(vertexInfo: VertexInfo) = vertexInfo.greaterRegion.width
    def layerWidth(layerInfo: LayerInfo) = {
      val vertexInfos = layerInfo.vertexInfos.values
      val spacing = vertexInfos.size
      vertexInfos.map(vertexWidth).sum + spacing
    }
    layerInfos.values.map(layerWidth).fold(0)(_ max _)
  }

  private def spaceVertices(layerInfos: Map[Layer, LayerInfo]): Map[Layer, LayerInfo] = {
    val diagramWidth = calculateDiagramWidth(layerInfos)
    layerInfos.map { case (layer, info) ⇒ layer -> spaceVertices(layer, info, diagramWidth) }
  }

  /**
   * Space out vertices horizontally across the full width of the diagram
   */
  private def spaceVertices(layer: Layer, layerVertexInfos: LayerInfo, diagramWidth: Int): LayerInfo = {
    val excessSpace = diagramWidth - layerVertexInfos.maxColumn
    val horizontalSpacing = math.max(excessSpace / (layerVertexInfos.vertexInfos.size + 1), 1)

    var leftColumn = horizontalSpacing
    val newVertexInfos =
      for {
        v ← layer.vertices
        vertexInfo ← layerVertexInfos.vertexInfo(v)
      } yield {
        val oldLeftColumn = leftColumn
        leftColumn += vertexInfo.greaterRegion.width
        leftColumn += horizontalSpacing
        v -> vertexInfo.setLeft(oldLeftColumn)
      }
    LayerInfo(newVertexInfos.toMap)
  }

  private case class LayerLayoutResult(
    drawingElements: List[DrawingElement],
    layerInfo: LayerInfo,
    updatedIncompletedEdges: Map[DummyVertex, List[Point]])

  /**
   * 1) Decide the (vertical) order of edges coming into the currentLayer -- that is, what row they bend on (if required)
   * 2) Decide the vertical position of the vertices in the currentLayer.
   * 3) Render the incoming edges and current layer vertices into diagram elements.
   * 4) Update bookkeeping information about as-yet-incomplete edges.
   *
   * @param edges -- edges from the previous layer into the current layer
   * @param incompleteEdges -- map from a dummy vertex in previous layer (which is the bottom-most tip of an incomplete
   *                           long edge), to the sequence of points that make up the edge built so far.
   */
  private def layoutLayer(
    previousLayerInfo: LayerInfo,
    currentLayerInfo: LayerInfo,
    edges: List[Edge],
    incompleteEdges: Map[DummyVertex, List[Point]]): LayerLayoutResult = {

    val edgeInfos: List[EdgeInfo] = makeEdgeInfos(edges, previousLayerInfo, currentLayerInfo)

    val edgeZoneTopRow = if (previousLayerInfo.isEmpty) -1 /* first layer */ else previousLayerInfo.maxRow + 1
    val edgeBendCalculator = new EdgeBendCalculator(edgeInfos, edgeZoneTopRow, currentLayerInfo.topSelfEdgeBuffer)

    val edgeInfoToPoints: Map[EdgeInfo, List[Point]] =
      makeMap(edgeInfos, edgeInfo ⇒ getEdgePoints(edgeInfo, edgeBendCalculator, incompleteEdges))

    val updatedIncompleteEdges: Map[DummyVertex, List[Point]] =
      for ((EdgeInfo(_, finishVertex: DummyVertex, _, _, _), points) ← edgeInfoToPoints)
        yield finishVertex -> points.init

    val updatedLayerInfo = currentLayerInfo.down(edgeBendCalculator.edgeZoneBottomRow + 1)

    val selfEdgeElements = updatedLayerInfo.vertexInfos.collect {
      case (realVertex: RealVertex, vertexInfo) ⇒
        val boxRightEdge = vertexInfo.boxRegion.rightColumn
        vertexInfo.selfOutPorts.zip(vertexInfo.selfInPorts).reverse.zipWithIndex map {
          case ((out, in), i) ⇒
            val p1 = out.down(1)
            val p2 = p1.down(i + 1)
            val p3 = p2.right(boxRightEdge - p2.column + i * 2 + 1)
            val p4 = p3.up(vertexInfo.boxRegion.height + 2 * (i + 1) + 1)
            val p5 = p4.left(p4.column - in.column)
            val p6 = in.up(1)
            EdgeDrawingElement(List(p1, p2, p3, p4, p5, p6), false, true)
        }
    }.toList.flatten

    val vertexElements = makeVertexElements(updatedLayerInfo)
    val edgeElements = makeEdgeElements(edgeInfoToPoints)
    LayerLayoutResult(vertexElements ++ edgeElements ++ selfEdgeElements, updatedLayerInfo, updatedIncompleteEdges)
  }

  private def makeEdgeInfos(edges: List[Edge], previousLayerInfo: LayerInfo, currentLayerInfo: LayerInfo): List[EdgeInfo] =
    for {
      edge @ Edge(v1, v2) ← edges
      previousVertexInfo ← previousLayerInfo.vertexInfo(v1)
      currentVertexInfo ← currentLayerInfo.vertexInfo(v2)
      start = previousVertexInfo.outEdgeToPortMap(edge).down
      finish = currentVertexInfo.inEdgeToPortMap(edge).up // Note that this will be at the wrong absolute vertical position, we'll adjust later
    } yield EdgeInfo(v1, v2, start, finish, edge.reversed)

  private def getEdgePoints(edgeInfo: EdgeInfo, edgeBendCalculator: EdgeBendCalculator, incompleteEdges: Map[DummyVertex, List[Point]]): List[Point] = {
    val EdgeInfo(startVertex, _, start, finish, _) = edgeInfo
    val trueFinish = finish.translate(down = edgeBendCalculator.edgeZoneBottomRow + 1)
    val priorPoints: List[Point] = startVertex match {
      case dv: DummyVertex ⇒ incompleteEdges(dv)
      case _: RealVertex   ⇒ List(start)
    }
    val lastPriorPoint = priorPoints.last
    if (lastPriorPoint.column == trueFinish.column) // No bend required
      priorPoints :+ trueFinish
    else {
      val row = edgeBendCalculator.bendRow(edgeInfo)
      priorPoints ++ List(lastPriorPoint.copy(row = row), trueFinish.copy(row = row), trueFinish)
    }
  }

  private def makeEdgeElements(edgeInfoToPoints: Map[EdgeInfo, List[Point]]): List[EdgeDrawingElement] =
    for ((EdgeInfo(_, finishVertex: RealVertex, _, _, reversed), points) ← edgeInfoToPoints.toList)
      yield EdgeDrawingElement(points, reversed, !reversed)

  private def makeVertexElements(layerInfo: LayerInfo): List[VertexDrawingElement] =
    layerInfo.realVertexInfos.map {
      case (realVertex, info) ⇒
        val text = getText(vertexRenderingStrategy, realVertex, info.contentRegion.dimension)
        VertexDrawingElement(info.boxRegion, text)
    }

  private def getPreferredSize[V](vertexRenderingStrategy: VertexRenderingStrategy[V], realVertex: RealVertex) =
    vertexRenderingStrategy.getPreferredSize(realVertex.contents.asInstanceOf[V])

  private def getText[V](vertexRenderingStrategy: VertexRenderingStrategy[V], realVertex: RealVertex, preferredSize: Dimension) =
    vertexRenderingStrategy.getText(realVertex.contents.asInstanceOf[V], preferredSize)

}

