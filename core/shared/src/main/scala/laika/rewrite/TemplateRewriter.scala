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

package laika.rewrite

import cats.implicits._
import laika.ast._
import laika.config.Origin.TemplateScope
import laika.config.{ConfigError, LaikaKeys, Origin}
import laika.rewrite.ReferenceResolver.CursorKeys
import laika.rewrite.nav.ChoiceGroupsConfig

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer

trait TemplateRewriter {

  /** The default template to use if no user-defined template exists.
    * 
    * The default simply inserts the rendered document into the rendered result without any surrounding template text.
    */
  val defaultTemplate: TemplateDocument = TemplateDocument(Path.Root / "default.template", 
    TemplateRoot(TemplateContextReference(CursorKeys.documentContent, required = true)))
  
  /** Selects and applies the templates for the specified output format to all documents within the specified tree cursor recursively.
   */
  def applyTemplates (tree: DocumentTreeRoot, format: String): Either[ConfigError, DocumentTreeRoot] = applyTemplates(RootCursor(tree), format)

  private def applyTemplates (cursor: RootCursor, format: String): Either[ConfigError, DocumentTreeRoot] = {
    
    for {
      newCover <- cursor.coverDocument.traverse(applyTemplate(_, format))
      newTree  <- applyTemplates(cursor.tree, format)
    } yield {
      cursor.target.copy(
        coverDocument = newCover,
        tree = newTree
      )
    }
    
  }
  
  private def applyTreeTemplate (cursors: Seq[Cursor], format: String): Either[ConfigError, Seq[TreeContent]] =
    cursors.foldLeft[Either[ConfigError, Seq[TreeContent]]](Right(Nil)) {
      case (acc, next) => acc.flatMap { ls => (next match {
        case doc: DocumentCursor => applyTemplate(doc, format)
        case tree: TreeCursor => applyTemplates(tree, format)
      }).map(ls :+ _) }
    }
  
  private def applyTemplates (cursor: TreeCursor, format: String): Either[ConfigError, DocumentTree] = {

    for {
      newTitle   <- cursor.titleDocument.traverse(applyTemplate(_, format))
      newContent <- applyTreeTemplate(cursor.children, format)
    } yield {
      cursor.target.copy(
        titleDocument = newTitle,
        content = newContent,
        templates = Nil
      )
    }
    
  }
  
  private def applyTemplate (cursor: DocumentCursor, format: String): Either[ConfigError, Document] = {
    val template = selectTemplate(cursor, format).getOrElse(defaultTemplate)
    applyTemplate(cursor, template)
  }

  /** Applies the specified template to the target of the specified document cursor.
    */
  def applyTemplate (cursor: DocumentCursor, template: TemplateDocument): Either[ConfigError, Document] = {
    template.config.resolve(Origin(TemplateScope, template.path), cursor.config, cursor.root.target.includes).map { mergedConfig =>
      val cursorWithMergedConfig = cursor.copy(
        config = mergedConfig,
        resolver = ReferenceResolver.forDocument(cursor.target, cursor.parent, mergedConfig, cursor.position),
        templatePath = Some(template.path)
      )
      val newContent = rewriteRules(cursorWithMergedConfig).rewriteBlock(template.content)
      val newRoot = newContent match {
        case TemplateRoot(List(TemplateElement(root: RootElement, _, _)), _) => root
        case TemplateRoot(List(EmbeddedRoot(content, _, _)), _) => RootElement(content)
        case other => RootElement(other)
      }
      cursorWithMergedConfig.target.copy(content = newRoot)
    }
  }
  
  private[laika] def selectTemplate (cursor: DocumentCursor, format: String): Option[TemplateDocument] = {
    val config = cursor.config
    val templatePath = config.get[Path](LaikaKeys.template).toOption // TODO - error handling 
      .orElse(config.get[Path](LaikaKeys.template(format)).toOption)

    templatePath match {
      case Some(path) =>
        cursor.root.target.tree.selectTemplate(path.relative) // TODO - error handling 

      case None =>
        val filename = "default.template." + format

        @tailrec def templateForTree(tree: TreeCursor): Option[TemplateDocument] =
          (tree.target.selectTemplate(filename), tree.parent) match {
            case (None, Some(parent)) => templateForTree(parent)
            case (Some(template), _) => Some(template)
            case (None, None) => None
          }

        templateForTree(cursor.parent)
    }
  }
  
  /** The default rewrite rules for template documents,
    * responsible for replacing all span and block resolvers with the final resolved element they produce 
    * based on the specified document cursor and its configuration.
    */
  def rewriteRules (cursor: DocumentCursor): RewriteRules = {
    
    // maps group name to selected choice name
    val choices: Map[String, String] = cursor.root.config
      .get[ChoiceGroupsConfig]
      .getOrElse(ChoiceGroupsConfig(Nil))
      .choices
      .flatMap(group => group.choices.find(_.selected).map(c => (group.name, c.name)))
      .toMap
    
    def select (group: ChoiceGroup, selectedChoice: String): Block = group.choices
      .find(_.name == selectedChoice)
      .fold[Block](group)(choice => BlockSequence(choice.content))
    
    lazy val rules: RewriteRules = RewriteRules.forBlocks {
      case ph: BlockResolver                => Replace(rewriteBlock(ph.resolve(cursor)))
      case ch: ChoiceGroup if choices.contains(ch.name) => Replace(select(ch, choices(ch.name)))
      case TemplateRoot(spans, opt)         => Replace(TemplateRoot(format(spans), opt))
      case unresolved: Unresolved           => Replace(InvalidElement(unresolved.unresolvedMessage, "<unknown source>").asBlock)
      case sc: SpanContainer with Block     => Replace(sc.withContent(joinTextSpans(sc.content)).asInstanceOf[Block])
    } ++ RewriteRules.forSpans {
      case ph: SpanResolver                 => Replace(rewriteSpan(ph.resolve(cursor)))
      case unresolved: Unresolved           => Replace(InvalidElement(unresolved.unresolvedMessage, "<unknown source>").asSpan)
      case sc: SpanContainer with Span      => Replace(sc.withContent(joinTextSpans(sc.content)).asInstanceOf[Span])
    } ++ RewriteRules.forTemplates {
      case ph: SpanResolver                 => Replace(rewriteTemplateSpan(asTemplateSpan(ph.resolve(cursor))))
      case TemplateSpanSequence(spans, opt) => Replace(TemplateSpanSequence(format(spans), opt))
      case unresolved: Unresolved           => Replace(InvalidElement(unresolved.unresolvedMessage, "<unknown source>").asTemplateSpan)
    }
    
    def asTemplateSpan (span: Span) = span match {
      case t: TemplateSpan => t
      case s => TemplateElement(s)
    } 
    def rewriteBlock (block: Block): Block = rules.rewriteBlock(block)
    def rewriteSpan (span: Span): Span = rules.rewriteSpan(span)
    def rewriteTemplateSpan (span: TemplateSpan): TemplateSpan = rules.rewriteTemplateSpan(span)
    
    def joinTextSpans (spans: Seq[Span]): Seq[Span] = if (spans.isEmpty) spans
      else spans.sliding(2).foldLeft(spans.take(1)) {
        case (acc, Seq(Text(_, NoOpt), Text(txt2, NoOpt))) => 
          acc.dropRight(1) :+ Text(acc.last.asInstanceOf[Text].content + txt2)
        case (acc, Seq(_, other)) => acc :+ other
        case (acc, _) => acc
      }
    
    def format (spans: Seq[TemplateSpan]): Seq[TemplateSpan] = {
      def indentFor(text: String): Int = text.lastIndexOf('\n') match {
        case -1    => 0
        case index => if (text.drop(index).trim.isEmpty) text.length - index - 1 else 0
      }
      if (spans.isEmpty) spans
      else spans.sliding(2).foldLeft(new ListBuffer[TemplateSpan]() += spans.head) { 
        case (buffer, Seq(TemplateString(text, NoOpt), TemplateElement(elem, 0, opt))) => 
          buffer += TemplateElement(elem, indentFor(text), opt)
        case (buffer, Seq(TemplateString(text, NoOpt), EmbeddedRoot(elem, 0, opt))) =>
          buffer += EmbeddedRoot(elem, indentFor(text), opt)
        case (buffer, Seq(_, elem)) => buffer += elem
        case (buffer, _) => buffer
      }.toList
    }
    rules
  }
  
}

object TemplateRewriter extends TemplateRewriter
