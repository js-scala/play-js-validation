package scala.jsvalidation

import play.api._
import play.api.mvc._

import play.api.data._
import Forms._
import format.Formats._
import validation._
import validation.Constraints._

import scala.js._

object PlayLMS {
  abstract class JSConstraint[-T:Manifest](name: String, errorName: String, args: Seq[Any], f: T => Boolean) extends Constraint[T](Some(name), args)(o => if (f(o)) Valid else Invalid(ValidationError(errorName, args: _*))) {
    val jsExp: JSExp
    val fExp: jsExp.Rep[Array[Any]] => jsExp.Rep[T => Boolean]

    private def codegen = new JSGen { val IR: jsExp.type = jsExp }

    def jsName = name.replace(".", "$")
    def validatorRule = jsName + " : " + (if (args.isEmpty) "true" else args.map(x => codegen.quote(jsExp.Const(x))).mkString("[", ",", "]"))
    def validatorCode(Messages: String => String): String = {
      val writer = new java.io.StringWriter
      jsExp.reset
      codegen.emitSource(fExp, "", new java.io.PrintWriter(writer))
      val fCode = writer.toString
      val res =
        "jQuery.validator.addMethod(\"" + jsName + "\", function(value, element, params) {\n" +
      "return this.optional(element) || ((" + fCode + ")(params))(value);\n" +
      "}, jQuery.format(\"" + Messages(errorName) + "\"));\n" 
       res
     }
  }

  private def newInScala = new JSInScala {}
  private def newInJS = new JSExp {
    def wrap[T:Manifest](f: Rep[T] => Rep[Boolean]): Rep[Array[Any]] => Rep[T => Boolean] =
      (_ : Rep[Array[Any]]) => f
  }


  def jsConstraint[T:Manifest](name: String, errorName: String)(prog: { def eval(c: JS): c.Rep[T] => c.Rep[Boolean] }) = {
    val inScala = newInScala
    val inJS = newInJS

    val f = prog.eval(inScala)
    val fExpArg = inJS.wrap(prog.eval(inJS))
    new JSConstraint[T](name, errorName, Seq[Any](), f) {
      override val jsExp: inJS.type = inJS
      override val fExp = fExpArg
    }
  }

  def jsParametricConstraint[T:Manifest](name: String, errorName: String)(prog: { def eval(c: JS): c.Rep[Array[Any]] => c.Rep[T => Boolean] }) = new { def apply(args: Any*) = {
    val inScala = newInScala
    val inJS = newInJS

    val f = prog.eval(inScala)
    val fExpArg = prog.eval(inJS)
    new JSConstraint[T](name, errorName, args, f(args.toArray)) {
      override val jsExp: inJS.type = inJS
      override val fExp = fExpArg
    }
  }}

  def jsPattern(regex: String, name: String = "constraint.pattern", error: String = "error.pattern") = {
    if (error != "error.pattern") {
      assert(name != "constraint.pattern", "You need to change the name if you change the error.")
    }

    val whole_regex =
      (if (regex.startsWith("^")) "" else "^") +
      regex +
      (if (regex.endsWith("$")) "" else "$")
    val pc = jsParametricConstraint(name, error) { new Serializable { def eval(c: JS) = {
      import c._;
      (params: Rep[Array[Any]]) => fun { (str: Rep[String]) =>
        params(0).asInstanceOf[Rep[String]].r.test(str) }
    }}}
    pc(whole_regex)
  }

  // Hack: top.mappings doesn't return what we want, because
  //   it goes too deep into field mappings,
  //   leaving us without knowledge of whether a mapping is optional.
  def directMappings(top: Mapping[_]): Seq[(String, Mapping[_])] = {
    top match {
      case prod : Product =>
        prod.productIterator.toSeq.collect{ case t : (String, Mapping[_]) => t }
    }
  }

  def generateJS[T](Messages: String => String)(top: Mapping[T]) = { id: String =>
    var validators : Map[String, String] = Map()
    var res = ""
    res += "rules : {\n"

    for ((key, dm) <- directMappings(top)) {
      val (isRequired, m) = dm match {
        case om : OptionalMapping[_] => (false, om.wrapped)
        case _ => (true, dm)
      }
      if (!m.constraints.isEmpty) {
        res += key + ": {\n"
        if (isRequired) res += "required : true,\n"
        for (c <- m.constraints) {
          c match {
            case jsC : JSConstraint[_] => {
              res += jsC.validatorRule + ",\n"
              if (!validators.contains(jsC.jsName))
                validators += ((jsC.jsName, jsC.validatorCode(Messages)))
            }
            case _ => ()
          }
        }
        res += "},\n"
      }
    }

    res += "}\n"

    res = "$(document).ready(function(){\n$(\"" + id + "\").validate({" + res + "})\n})"

    res = validators.values.mkString("\n") + res

    res
  }
}
