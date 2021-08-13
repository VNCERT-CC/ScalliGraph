package org.thp.scalligraph.traversal

import org.thp.scalligraph.controllers.{Output, Renderer}
import play.api.libs.json.{JsArray, JsValue}

class IteratorOutput(val iterator: Iterator[JsValue], val totalSize: Option[() => Long]) extends Output {
  override def content: JsValue = JsArray(iterator.toSeq)
}

object IteratorOutput extends TraversalOps {
  def apply[V](traversal: Traversal[V, _, _], totalSize: Option[() => Long] = None)(implicit renderer: Renderer[V]) =
    new IteratorOutput(traversal.cast[V, Any].toIterator.map(renderer.toJson), totalSize)
}
