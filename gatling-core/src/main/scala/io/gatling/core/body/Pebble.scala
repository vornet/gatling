/*
 * Copyright 2011-2020 GatlingCorp (https://gatling.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.gatling.core.body

import java.{ util => ju }

import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.collection.mutable
import scala.util.control.NonFatal

import io.gatling.commons.validation._
import io.gatling.core.session.Session
import io.gatling.core.util.{ ClasspathFileResource, ClasspathPackagedResource, FilesystemResource, Resource }

import com.mitchellbosecke.pebble.PebbleEngine
import com.mitchellbosecke.pebble.extension.Extension
import com.mitchellbosecke.pebble.extension.writer.PooledSpecializedStringWriter
import com.mitchellbosecke.pebble.loader.StringLoader
import com.mitchellbosecke.pebble.template.PebbleTemplate
import com.typesafe.scalalogging.StrictLogging

private[gatling] object PebbleExtensions {

  private[body] var extensions: Seq[Extension] = Nil

  def register(extensions: Seq[Extension]): Unit = {
    if (this.extensions.nonEmpty) {
      throw new UnsupportedOperationException("Pebble extensions have already been registered")
    }
    this.extensions = extensions
  }
}

private[gatling] object Pebble extends StrictLogging {

  private val StringEngine = new PebbleEngine.Builder().autoEscaping(false).extension(PebbleExtensions.extensions: _*).loader(new StringLoader).build
  private val DelegatingEngine = new PebbleEngine.Builder().autoEscaping(false).extension(PebbleExtensions.extensions: _*).build

  private[body] def toJava(map: Map[String, Any]): ju.Map[String, AnyRef] = {
    val jMap = new ju.HashMap[String, AnyRef](map.size)
    for ((k, v) <- map) {
      val javaValue = v match {
        case c: mutable.Seq[_]      => c.asJava
        case c: immutable.Seq[_]    => c.asJava
        case m: mutable.Map[_, _]   => m.asJava
        case m: immutable.Map[_, _] => m.asJava
        case m: mutable.Set[_]      => m.asJava
        case m: immutable.Set[_]    => m.asJava
        case any: AnyRef            => any // the AnyVal case is not addressed, as an AnyVal will be in an AnyRef wrapper
      }
      jMap.put(k, javaValue)
    }
    jMap
  }

  def getStringTemplate(string: String): Validation[PebbleTemplate] =
    try {
      StringEngine.getTemplate(string).success
    } catch {
      case NonFatal(e) =>
        logger.error("Error while parsing Pebble string", e)
        e.getMessage.failure
    }

  def getResourceTemplate(resource: Resource): Validation[PebbleTemplate] =
    try {
      val templateName = resource match {
        case ClasspathPackagedResource(path, _) => path
        case ClasspathFileResource(path, _)     => path
        case FilesystemResource(file)           => file.getAbsolutePath
      }

      DelegatingEngine.getTemplate(templateName).success
    } catch {
      case NonFatal(e) =>
        logger.error(s"Error while parsing Pebble template $resource", e)
        e.getMessage.failure
    }

  def evaluateTemplate(template: PebbleTemplate, session: Session): Validation[String] = {
    val context = toJava(session.attributes)
    val writer = PooledSpecializedStringWriter.pooled
    try {
      template.evaluate(writer, context)
      writer.toString.success
    } catch {
      case NonFatal(e) =>
        logger.info("Error while evaluate Pebble template", e)
        e.getMessage.failure
    }
  }
}
