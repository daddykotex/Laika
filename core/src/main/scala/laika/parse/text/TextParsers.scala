/*
 * Copyright 2013-2016 the original author or authors.
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

package laika.parse.text

import laika.ast.{~, Size}
import laika.parse.{Failure, Message, Parser, Success}
import laika.parse.combinator.Parsers

/** Base text parsers that provide optimized low-level parsers for typical requirements
 *  of text markup parsers. In particular they are meant as an efficient replacement
 *  for scenarios where usually regex parsers are used. In cases where different parsers
 *  need to be tried for relatively short input sequences, regex parsers tend to be less
 *  efficient. Furthermore, these base parsers may also improve readability, as it
 *  allows to combine simple low-level parsers to higher-level parsers based on the
 *  Laika combinator API, instead of producing long regexes which may be hard to read.
 * 
 *  @author Jens Halm
 */
object TextParsers extends Parsers {

  
  /** Implicit conversion that allows to pass a single
   *  character to the range-based `anyIn` parser. 
   */
  implicit def charToTraversable (char: Char): Traversable[Char] = Set(char)

  /**  A parser that matches only the specified character.
    *
    *  The method is implicit so that characters can automatically be lifted to their parsers.
    */
  implicit def char (expected: Char): Parser[Char] = {
    val errMsg: Char => Message = Message.forRuntimeValue[Char] { found => s"'$expected' expected but $found found" }
    Parser { in =>
      if (in.atEnd) Failure(Message.UnexpectedEOF, in)
      else if (in.char == expected) Success(in.char, in.consume(1))
      else Failure(errMsg(in.char), in)
    }
  }

  /**  A parser that matches only the specified literal string.
    *
    *  The method is implicit so that strings can automatically be lifted to their parsers.
    */
  implicit def literal (expected: String): Parser[String] = Literal(expected)

  /** Parses horizontal whitespace (space and tab).
    * Always succeeds, consuming all whitespace found.
    */
  lazy val ws: Characters[String] = anyOf(' ','\t')

  /** Succeeds at the end of a line, including the end of the input.
   *  Produces an empty string as a result and consumes any new line characters.
   */
  val eol: Parser[Unit] = Parser { in =>
    if (in.atEnd) Success((), in)
    else if (in.char == '\n') Success((), in.consume(1))
    else if (in.char == '\r' && in.remaining > 1 && in.charAt(1) == '\n') Success((), in.consume(2))
    else Failure(Message.ExpectedEOL, in)
  }

  /** Parses any number of whitespace characters followed
    * by a newline character.
    */
  val wsEol: Parser[Unit] = ws.^ ~> eol
  
  /** Succeeds at the end of the input.
   */
  val eof: Parser[String] = Parser { in =>
    if (in.atEnd) Success("", in)
    else Failure(Message.ExpectedEOF, in)
  }  
  
  /** Succeeds at the start of the input.
   */
  val atStart: Parser[Unit] = Parser { in =>
    if (in.offset == 0) Success(success(()), in) 
    else Failure(Message.ExpectedStart, in)
  }

  /** Parses a blank line from the current input offset (which may not be at the
    *  start of the line). Fails for lines that contain any non-whitespace character.
    *  Does always produce an empty string as the result, discarding any whitespace
    *  characters found in the line.
    *
    *  Since it also succeeds at the end of the input
    *  it should never be used in the form of `(blankLine *)` or `(blankLine +)`. Use
    *  the `blankLines` parser instead in these cases.
    */
  val blankLine: Parser[String] = wsEol ^^^ ""

  /** Parses one or more blanklines, producing a list of empty strings corresponding
    *  to the number of blank lines consumed.
    */
  val blankLines: Parser[List[String]] = (not(eof) ~> blankLine)+

  /** Parses the rest of the line from the current input offset no matter whether
    *  it consist of whitespace only or some text. Does not include the eol character(s).
    */
  val restOfLine: Parser[String] = anyBut('\n','\r') <~ eol

  /** Parses a single text line from the current input offset (which may not be at the
    *  start of the line). Fails for blank lines. Does not include the eol character(s).
    */
  val textLine: Parser[String] = not(blankLine) ~> restOfLine

  /** Parses a simple reference name that only allows alphanumerical characters
   *  and the punctuation characters `-`, `_`, `.`, `:`, `+`.
   */
  val refName: Parser[String] = {
    val alphanum = anyWhile(c => Character.isDigit(c) || Character.isLetter(c)) min 1
    val symbol = anyOf('-', '_', '.', ':', '+') take 1
    
    alphanum ~ ((symbol ~ alphanum)*) ^^ { 
      case start ~ rest => start + (rest map { case a~b => a+b }).mkString
    }
  }

  /** Parses a size and its amount, e.g. 12px.
    * The unit is mandatory and not validated.
    */
  val sizeAndUnit: Parser[Size] = {
    val digit = anyIn('0' to '9').min(1)
    val amount = digit ~ opt("." ~ digit) ^^ {
      case num1 ~ Some(_ ~ num2) => s"$num1.$num2".toDouble
      case num ~ None => num.toDouble
    }
    amount ~ (ws ~> (refName | "%")) ^^ { case amount ~ unit => Size(amount, unit) }
  }

  /** Consumes any kind of input, always succeeds.
   *  This parser would consume the entire input unless a `max` constraint
   *  is specified.
   */
  val any: Characters[String] = Characters.anyWhile(_ => true)
  
  /** Consumes any number of consecutive occurrences of the specified characters.
   *  Always succeeds unless a minimum number of required matches is specified.
   */
  def anyOf (chars: Char*): Characters[String] = Characters.include(chars)
  
  /** Consumes any number of consecutive characters that are not one of the specified characters.
   *  Always succeeds unless a minimum number of required matches is specified.
   */
  def anyBut (chars: Char*): Characters[String] = Characters.exclude(chars)
  
  /** Consumes any number of consecutive characters that are in one of the specified character ranges.
   *  Always succeeds unless a minimum number of required matches is specified.
   */
  def anyIn (ranges: Traversable[Char]*): Characters[String] = Characters.include(ranges.flatten)

  /** Consumes any number of consecutive characters which satisfy the specified predicate.
    *  Always succeeds unless a minimum number of required matches is specified.
    */
  def anyWhile (p: Char => Boolean): Characters[String] = Characters.anyWhile(p)


  /** Consumes any number of consecutive characters until one of the specified characters
    * is encountered on the input string.
    */
  def delimitedBy (chars: Char*): DelimitedText[String] with DelimiterOptions =
    DelimiterOptions(ConfigurableDelimiter(chars.toSet))

  /** Consumes any number of consecutive characters until the specified string delimiter
    * is encountered on the input string.
    */
  def delimitedBy (str: String): DelimitedText[String] with DelimiterOptions = {
    val len = str.length
    if (len == 0) DelimitedText.Undelimited
    else if (len == 1) DelimiterOptions(ConfigurableDelimiter(Set(str.head)))
    else delimitedBy(str.head.toString, Literal(str.tail))
  }

  /** Consumes any number of consecutive characters until the specified string delimiter
    * is encountered on the input string.
    *
    * Only succeeds if the specified `postCondition` parser succeeds at the offset after
    * the consumed delimiter string.
    */
  def delimitedBy (str: String, postCondition: Parser[Any]): DelimitedText[String] with DelimiterOptions = {
    val len = str.length
    if (len == 0) DelimitedText.Undelimited
    else if (len == 1) DelimiterOptions(ConfigurableDelimiter(Set(str.head), Some(postCondition)))
    else delimitedBy(str.head.toString, Literal(str.tail) ~ postCondition)
  }

}