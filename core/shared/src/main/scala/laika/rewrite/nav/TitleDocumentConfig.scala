/*
 * Copyright 2012-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package laika.rewrite.nav

import laika.ast.Path
import laika.config.Config

/** Configuration for the names of title documents in the input and output trees.
  * 
  * @author Jens Halm
  */
object TitleDocumentConfig {

  private val defaultInputName: String = "title"
  private val defaultOutputName: String = "title"

  /** The name to denote a title document in an input tree as configured in the specified instance.
    */
  def inputName (config: Config): String =
    config.get[String]("titleDocuments.inputName").toOption.getOrElse(defaultInputName)

  /** The name to assign to a title document before rendering as configured in the specified instance.
    */
  def outputName (config: Config): String =
    config.get[String]("titleDocuments.outputName").toOption.getOrElse(defaultOutputName)

  /** Translates the specified input path to an output path by applying the given suffix
    * and adjusting the base name in case it is a title document and configured input and output names differ. 
    * 
    * @param input the path to translate
    * @param suffix the suffix to apply to the result
    * @param config the configuration to read configured title document names from
    * @return the translated path that can be used by renderers
    */
  def inputToOutput (input: Path, suffix: String, config: Config): Path = {
    if (input.basename == inputName(config)) (input.parent / outputName(config)).withSuffix(suffix)
    else input.withSuffix(suffix)
  }
  
}
