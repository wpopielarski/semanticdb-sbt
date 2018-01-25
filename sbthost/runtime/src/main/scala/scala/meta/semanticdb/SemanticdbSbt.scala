package scala.meta
package semanticdb

import scala.collection.mutable
import scala.meta.tokens.Token.Ident

object SemanticdbSbt {
  private def label(doc: Document) = doc.input match {
    case Input.VirtualFile(label, _) => label
    case els => els.toString
  }

  private val isSbtDialect = Set("Scala212", "Sbt1")

  def patchDatabase(db: Database, sourceroot: AbsolutePath): Database = {
    if (!db.documents.exists { x =>
      isSbtDialect(x.language)
    }) {
      // Optimization. Some databases can be quite large and most of dbs
      // from applications like scalafix will not have sbt semanticdbs.
      db
    } else {
      val entries = db.documents.groupBy(label).map {
        case (label, doc) if label.endsWith(".sbt") =>
          sbtFile(Input.File(sourceroot.resolve(label)), doc.toList)
        case (_, a +: Nil) => scalaFile(a)
      }
      Database(entries.toList)
    }
  }

  private def sbtFile(input: Input, doc: List[Document]): Document = {
    val source = dialects.Sbt1(input).parse[Source].get
    val (definitions, exprs) = source.stats.partition(_.is[Defn])
    val tokenMap: Map[Token, (Int, Token)] = {
      val builder = Map.newBuilder[Token, (Int, Token)]
      val definitionTokens = definitions.mkString("\n\n").tokenize.get
      val reader = definitionTokens.toIterator.drop(1) // drop BOF
      var stack = definitions
      while (stack.nonEmpty && reader.hasNext) {
        val definition = stack.head
        definition.tokens.foreach { from =>
          val to = reader.next()
          assert(from.syntax == to.syntax, s"$from == $to")
          builder += (from -> (0, to))
        }
        stack = stack.tail
        if (stack.nonEmpty) {
          reader.next() // \n
          reader.next() // \n
        }
      }
      exprs.zipWithIndex.foreach {
        case (expr, i) =>
          val tokens = expr.tokens.mkString.tokenize.get.drop(1) // drop BOF
          expr.tokens.zip(tokens).foreach {
            case (a, b) =>
              builder += (a -> (i + 1, b))
          }
      }
      builder.result()
    }
    val lookup: Map[(Int, Int), List[ResolvedName]] = doc.zipWithIndex
      .flatMap {
        case (as, i) =>
          as.names.map {
            case r @ ResolvedName(pos, sym, _) =>
              (i -> pos.start, r)
          }
      }
      .groupBy(_._1)
      .mapValues(_.map(_._2))
    def label(symbol: Symbol): String = symbol match {
      case Symbol.Global(_, Signature.Type(name)) => name
      case Symbol.Global(_, Signature.Term(name)) => name
      case Symbol.Global(_, Signature.Method(name, _)) => name
      case _ => symbol.syntax
    }
    val buffer = List.newBuilder[ResolvedName]
    val taken = mutable.Set.empty[Position]
    source.tokens.foreach {
      case t @ Ident(value) =>
        tokenMap.get(t).foreach {
          case (idx, tok) =>
            lookup.get(idx -> tok.pos.start).foreach { syms =>
              syms.foreach { r =>
                val l = label(r.symbol)
                if (l == value && !taken(t.pos)) {
                  buffer += r.copy(position = t.pos)
                  taken += t.pos
                }
              }
            }
        }
      case _ =>
    }
    val document = Document(
      input = input,
      language = dialects.Sbt1.toString(),
      names = buffer.result(),
      messages = doc.flatMap(_.messages),
      symbols = doc.flatMap(_.symbols),
      synthetics = doc.flatMap(_.synthetics)
    )
    document
  }

  private def scalaFile(doc: Document): Document = {
    val endByStart = doc.input.tokenize.get.collect {
      case t: Ident => t.pos.start -> (t -> t.pos.end)
    }.toMap
    val isTaken = mutable.Set.empty[Int]
    val names: List[ResolvedName] = doc.names.collect {
      case ResolvedName(pos @ Position.Range(_, start, _), symbol, isBinder)
          if !isTaken(start) && endByStart.contains(start) =>
        isTaken += start
        ResolvedName(pos.copy(end = endByStart(start)._2), symbol, isBinder)
    }
    doc.copy(names = names)
  }
}
