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

package laika.format

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.{Date, Locale, UUID}

import cats.effect.{Resource, Sync}
import laika.api.builder.OperationConfig
import laika.ast.Path.Root
import laika.ast._
import laika.config.Config.ConfigResult
import laika.config._
import laika.factory._
import laika.io.model.{BinaryOutput, RenderedTreeRoot}
import laika.io.runtime.Runtime
import laika.render.epub.{ContainerWriter, StyleSupport, XHTMLRenderer}
import laika.render.{HTMLFormatter, XHTMLFormatter}
import laika.theme.config.FontDefinition
import laika.theme.{Theme, config}

/** A post processor for EPUB output, based on an interim HTML renderer.
 *  May be directly passed to the `Renderer` or `Transformer` APIs:
 *
  * {{{
  * implicit val cs: ContextShift[IO] = 
  *   IO.contextShift(ExecutionContext.global)
  *
  * val blocker = Blocker.liftExecutionContext(
  *   ExecutionContext.fromExecutor(Executors.newCachedThreadPool())
  * )
  *
  * val transformer = Transformer
  *   .from(Markdown)
  *   .to(EPUB)
  *   .using(GitHubFlavor)
  *   .io(blocker)
  *   .parallel[IO]
  *   .build
  *
  * val res: IO[Unit] = transformer
  *   .fromDirectory("src")
  *   .toFile("demo.epub")
  *   .transform
  * }}}
 *  
 *  In the example above the input from an entire directory gets
 *  merged into a single output file.
 * 
 *  @author Jens Halm
 */
case object EPUB extends TwoPhaseRenderFormat[HTMLFormatter, BinaryPostProcessorBuilder] {

  /** A render format for XHTML output as used by EPUB output.
    *
    * This format is usually not used directly with Laika's `Render` or `Transform` APIs.
    * It is primarily used internally by the parent `EPUB` instance.
    *
    *  @author Jens Halm
    */
  object XHTML extends RenderFormat[HTMLFormatter] {

    val fileSuffix: String = "epub.xhtml"

    val defaultRenderer: (HTMLFormatter, Element) => String = XHTMLRenderer

    val formatterFactory: RenderContext[HTMLFormatter] => HTMLFormatter = XHTMLFormatter

  }

  val interimFormat: RenderFormat[HTMLFormatter] = XHTML

  /** Configuration options for the generated EPUB output.
    *
    * The duplication of the existing `BookConfig` instance from laika-core happens to have a different
    * implicit key association with the EPUB-specific instance.
    *  
    * @param metadata the metadata associated with the document
    * @param navigationDepth the number of levels to generate a table of contents for
    * @param fonts the fonts that should be embedded in the EPUB container
    * @param coverImage the path to the cover image within the virtual document tree   
    */
  case class BookConfig(metadata: DocumentMetadata = DocumentMetadata(),
                        navigationDepth: Option[Int] = None,
                        fonts: Seq[FontDefinition] = Nil,
                        coverImage: Option[Path] = None) {
    lazy val identifier: String = metadata.identifier.getOrElse(s"urn:uuid:${UUID.randomUUID.toString}")
    lazy val date: Date = metadata.date.getOrElse(new Date)
    lazy val formattedDate: String = DateTimeFormatter.ISO_INSTANT.format(date.toInstant.truncatedTo(ChronoUnit.SECONDS))
    lazy val language: String = metadata.language.getOrElse(Locale.getDefault.getDisplayName)
  }
  
  object BookConfig {
    
    implicit val decoder: ConfigDecoder[BookConfig] = laika.theme.config.BookConfig.decoder.map(c => BookConfig(
      c.metadata, c.navigationDepth, c.fonts, c.coverImage
    ))
    implicit val encoder: ConfigEncoder[BookConfig] = laika.theme.config.BookConfig.encoder.contramap(c => 
      config.BookConfig(c.metadata, c.navigationDepth, c.fonts, c.coverImage)
    )
    implicit val defaultKey: DefaultKey[BookConfig] = DefaultKey(Key("laika","epub"))
    
    def decodeWithDefaults (config: Config): ConfigResult[BookConfig] = for {
      epubConfig   <- config.getOpt[BookConfig].map(_.getOrElse(BookConfig()))
      commonConfig <- config.getOpt[laika.theme.config.BookConfig].map(_.getOrElse(laika.theme.config.BookConfig()))
    } yield {
      BookConfig(
        epubConfig.metadata.withDefaults(commonConfig.metadata), 
        epubConfig.navigationDepth.orElse(commonConfig.navigationDepth),
        epubConfig.fonts ++ commonConfig.fonts,
        epubConfig.coverImage.orElse(commonConfig.coverImage)
      )
    }
    
  }
  
  private lazy val writer = new ContainerWriter

  /** Adds a cover image (if specified in the configuration)
    * and a fallback CSS resource (if the input tree did not contain any CSS),
    * before the tree gets passed to the XHTML renderer.
    */
  def prepareTree (tree: DocumentTreeRoot): Either[Throwable, DocumentTreeRoot] = {
    BookConfig.decodeWithDefaults(tree.config).map { treeConfig =>
      
      val treeWithStyles = StyleSupport.ensureContainsStyles(tree)
      treeConfig.coverImage.fold(tree) { image =>
        treeWithStyles.copy(tree = treeWithStyles.tree.copy(
          content = Document(Root / "cover", RootElement(SpanSequence(Image(InternalTarget(image), alt = Some("cover")))), 
          config = ConfigBuilder.empty.withValue(LaikaKeys.title, "Cover").build) +: tree.tree.content
        ))
      }
    }.left.map(ConfigException)
  }

  /** Produces an EPUB container from the specified result tree.
   *
   *  It includes the following files in the container:
   *
   *  - All text markup in the provided document tree, transformed to HTML by the specified render function.
   *  - All static content in the provided document tree, copied to the same relative path within the EPUB container.
   *  - Metadata and navigation files as required by the EPUB specification, auto-generated from the document tree
   *    and the configuration of this instance.
   */
  def postProcessor: BinaryPostProcessorBuilder = new BinaryPostProcessorBuilder {
    def build[F[_] : Sync](config: Config, theme: Theme[F]): Resource[F, BinaryPostProcessor] = Resource.pure(new BinaryPostProcessor {
      def process[G[_] : Sync : Runtime](result: RenderedTreeRoot[G], output: BinaryOutput[G], config: OperationConfig): G[Unit] =
        writer.write(result, output)
    })
  }
  
}
