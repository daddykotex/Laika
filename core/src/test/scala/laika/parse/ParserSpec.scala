/*
 * Copyright 2013-2018 the original author or authors.
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

package laika.parse

import laika.ast.~
import laika.parse.combinator.Parsers
import laika.parse.combinator.Parsers._
import laika.parse.helper.{ParseResultHelpers, StringParserHelpers}
import laika.parse.text.TextParsers
import org.scalatest.{Matchers, WordSpec}

/**
  * @author Jens Halm
  */
class ParserSpec extends WordSpec with Matchers with ParseResultHelpers with StringParserHelpers {


  val context = ParserContext("abc\ndef")

  val parser1 = TextParsers.anyOf('a','b').min(1)
  val parser2 = TextParsers.anyOf('b','c').min(1)


  "The monadic Parser" should {

    "provide the result of the parsing operation" in {
      Parsing("abc") using parser1 should produce ("ab")
    }

    "map the result" in {
      Parsing("abccbb") using parser1.map(_.length) should produce (2)
    }

    "flatMap with a different parser" in {
      Parsing("abccbb") using parser1.flatMap(res => parser2.max(res.length)) should produce ("cc")
    }

    "fail the flatMap when the first parser fails" in {
      Parsing("abccbb") using parser1.min(3).flatMap(res => parser2.max(res.length)) should cause [Failure]
    }

    "fail the flatMap when the second parser fails" in {
      Parsing("abccbb") using parser1.flatMap(_ => parser2.min(5)) should cause [Failure]
    }

    "provide a fixed result" in {
      Parsing("abccbb") using (parser1 ^^^ 7) should produce (7)
    }

    "apply a partial function to the result" in {
      Parsing("abc") using (parser1 ^? { case "ab" => 9 }) should produce (9)
    }

    "fail if the specified partial function is not defined for the result" in {
      Parsing("abc") using (parser1 ^? { case "xx" => 9 }) should cause [Failure]
    }

    "succeed if the specified Either function produces a Right" in {
      Parsing("abc") using (parser1 ^^? { res => Right(res.length) }) should produce (2)
    }

    "fail if the specified Either function produces a Left" in {
      Parsing("abc") using (parser1 ^^? { res => Left("wrong") }) should cause [Failure]
    }
  }


  "The alternatives of two parsers" should {

    val alternatives = parser1 | parser2

    "provide the result of the first parser if it succeeds" in {
      Parsing("bbcc") using alternatives should produce ("bb")
    }

    "provide the result of the second parser if the first fails, but the second succeeds" in {
      Parsing("ccbb") using alternatives should produce ("ccbb")
    }

    "fail if both parsers fail" in {
      Parsing("xxxx") using alternatives should cause [Failure]
    }

  }

  "The concatenation of two parsers" should {

    "provide the combined result" in {
      Parsing("aabbcc") using (parser1 ~ parser2) should produce (new ~("aabb", "cc"))
    }

    "fail if the first parser fails" in {
      Parsing("ccbbaa") using (parser1 ~ parser2) should cause [Failure]
    }

    "fail if the second parser fails" in {
      Parsing("aaffgg") using (parser1 ~ parser2) should cause [Failure]
    }

    "provide the first result" in {
      Parsing("aabbcc") using (parser1 <~ parser2) should produce ("aabb")
    }

    "provide the second result" in {
      Parsing("aabbcc") using (parser1 ~> parser2) should produce ("cc")
    }

  }

  "The optional parser" should {

    "produce a Some when the underlying parser succeeds" in {
      Parsing("abc") using (parser1 ?) should produce (Option("ab"))
    }

    "produce a None when the underlying parser fails" in {
      Parsing("xxx") using (parser1 ?) should produce (Option.empty[String])
    }

  }

  "The not parser" should {

    "fail when the underlying parser succeeds" in {
      Parsing("abc") using Parsers.not(parser1) should cause [Failure]
    }

    "succeed when the underlying parser fails" in {
      Parsing("xxx") using Parsers.not(parser1) should produce (())
    }

  }

  "The success parser" should {

    "always succeed" in {
      Parsing("foo") using success(9) should produce (9)
    }

  }

  "The failure parser" should {

    "always fail" in {
      Parsing("foo") using failure("expected failure") should cause [Failure]
    }

  }

  "The repetition parser for an arbitrary number of results" should {

    "produce an empty result when the first invocation fails" in {
      Parsing("xxx") using (parser1 *) should produce (List.empty[String])
    }

    "provide all matching substrings" in {
      Parsing("abacc") using parser1.max(1).rep should produce (List("a","b","a"))
    }

  }

  "The repetition parser for a minimum number of results" should {

    "fail if the required minimum number of successful invocations is not reached" in {
      Parsing("abaxx") using parser1.rep.min(4) should cause [Failure]
    }

    "succeed if at least one invocation succeeds" in {
      Parsing("abc") using parser1.max(1).+ should produce (List("a","b"))
    }

    "succeed if the specified number of successful invocations is reached" in {
      Parsing("aba") using parser1.max(1).rep.min(2) should produce (List("a","b","a"))
    }

  }

  "The repetition parser for an maximum number of results" should {

    "produce an empty result when the first invocation fails" in {
      Parsing("xxx") using parser1.rep.max(2) should produce (List.empty[String])
    }

    "provide only the maximum number of result allowed" in {
      Parsing("abacc") using parser1.max(1).rep.max(2) should produce (List("a","b"))
    }

  }

  "The parser for dynamic repetition" should {

    import TextParsers._

    val parser = literal("1").repWith { res:String => (res.toInt + 1).toString }

    "parse a sequence based on a dynamically changing parser" in {
      Parsing ("12345999") using parser should produce (List("1","2","3","4","5"))
    }

    "succeed when only the first parsing step succeeds" in {
      Parsing ("1999") using parser should produce (List("1"))
    }

    "succeed with an empty result when the first parsing step fails" in {
      Parsing ("999") using parser should produce (List[String]())
    }

  }

  "The lookAhead parser" should {

    import TextParsers._

    "succeed when the underlying parser succeeds at the current offset" in {
      Parsing("abcd") using lookAhead('a') should produce('a')
    }

    "succeed when the underlying parser succeeds at the specified offset" in {
      Parsing("abcd") using lookAhead(2, 'c') should produce('c')
    }

    "fail when the underlying parser fails at the current offset" in {
      Parsing("abcd") using lookAhead('c') should cause [Failure]
    }

    "fail when the underlying parser fails at the specified offset" in {
      Parsing("abcd") using lookAhead(2, 'a') should cause [Failure]
    }



  }

  "The lookBehind parser" should {

    val input = ParserContext("abcd").consume(2)

    import TextParsers._

    "succeed when the specified parser succeeds at the given negative offset" in {
      lookBehind(2, 'a').parse(input) should produce('a')
    }

    "fail when the specified parser fails at the given negative offset" in {
      lookBehind(2, 'b').parse(input) should cause[Failure]
    }

    "fail when the specified negative offset is too big" in {
      lookBehind(7, 'a').parse(input) should cause[Failure]
    }

  }

  "The consumeAll parser" should {

    "succeed when all input has been consumed" in {
      Parsing("ababa") using consumeAll(parser1) should produce("ababa")
    }

    "fail when not all input has been consumed" in {
      Parsing("ababaxx") using consumeAll(parser1) should cause[Failure]
    }

  }


}