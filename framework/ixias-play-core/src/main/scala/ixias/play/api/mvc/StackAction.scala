/*
 * This file is part of the IxiaS services.
 *
 * For the full copyright and license information,
 * please view the LICENSE file that was distributed with this source code.
 */

package ixias.play.api.mvc

import scala.reflect.ClassTag
import scala.util.{ Success, Failure }
import scala.util.control.{ NonFatal, ControlThrowable }
import scala.concurrent.{ Future, ExecutionContext }
import scala.collection.concurrent.TrieMap
import scala.language.higherKinds

import play.api.mvc._
import play.api.{ Play, Application }
import play.api.inject.{ Injector, BindingKey }

// Wrap an existing request. Useful to extend a request.
//~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
import StackActionRequest._
case class StackActionRequest[A](
  underlying: Request[A],
  attributes: TrieMap[AttributeKey[_], Any] = TrieMap.empty
) extends WrappedRequest[A](underlying) {

  /**
   * Retrieve an attribute by specific key.
   */
  def get[B](key: AttributeKey[B]): Option[B] =
    attributes.get(key).asInstanceOf[Option[B]]

  /**
   * Store an attribute under the specific key.
   */
  def set[B](key: AttributeKey[B], value: B): StackActionRequest[A] = {
    attributes.put(key, value)
    this
  }

  /**
   * Store an attributes
   */
  def ++=[B](tail: TrieMap[AttributeKey[_], Any]): StackActionRequest[A] = {
    attributes ++= tail
    this
  }
}

// The declaration for request's attribute.
//~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
object StackActionRequest {

  /**
   * The attribute key of request.
   */
  trait AttributeKey[A] {
    def ->(value: A): Attribute[A] = Attribute(this, value)
  }

  /**
   * The attribute of request.
   */
  case class Attribute[A](key: AttributeKey[A], value: A) {
    def toTuple: (AttributeKey[A], A) = (key, value)
  }
}

/**
 * A builder for generic Actions that generalizes over the type of requests.
 */
sealed trait StackActionInjector {

  /**
   * Get an instance of the given class from the injector.
   */
  def instanceOf[T: ClassTag]: Future[T] =
    Future(Play.routesCompilerMaybeApplication.map(
      _.injector.instanceOf[T]
    ).get)(play.api.libs.iteratee.Execution.trampoline)

  /**
   * Get an instance of the given class from the injector.
   */
  def instanceOf[T](clazz: Class[T]): Future[T] =
    Future(Play.routesCompilerMaybeApplication.map(
      _.injector.instanceOf[T](clazz)
    ).get)(play.api.libs.iteratee.Execution.trampoline)

  /**
   * Get an instance bound to the given binding key.
   */
  def instanceOf[T](key: BindingKey[T]): Future[T] =
    Future(Play.routesCompilerMaybeApplication.map(
      _.injector.instanceOf[T](key)
    ).get)(play.api.libs.iteratee.Execution.trampoline)
}

// Statck Action Function
//~~~~~~~~~~~~~~~~~~~~~~~~~
/**
 * Provides helpers for creating `Action` values.
 */
trait StackActionBuilder[+R[_]] extends ActionFunction[StackActionRequest, R] with StackActionInjector {
  self =>

  // --[ Methods ] -------------------------------------------------------------
  /**
   * Constructs an `Action` with default content, and no request parameter.
   */
  final def apply(block: => Result): Action[AnyContent] =
    apply(BodyParsers.parse.ignore(AnyContentAsEmpty: AnyContent))(_ => block)

  /**
   * Constructs an `Action` with default content
   */
  final def apply(block: R[AnyContent] => Result): Action[AnyContent] =
    apply(BodyParsers.parse.default)(block)

  /**
   * Constructs an `Action`.
   */
  final def apply[A](bodyParser: BodyParser[A])(block: R[A] => Result): Action[A] =
    async(bodyParser) { request: R[A] =>
      Future.successful(block(request))
    }

  // --[ Methods ] -------------------------------------------------------------
  /**
   * Constructs an `Action` that returns a future of a result,
   * with default content, and no request parameter.
   */
  final def async(block: => Future[Result]): Action[AnyContent] =
    async(BodyParsers.parse.ignore(AnyContentAsEmpty: AnyContent))(_ => block)

  /**
   * Constructs an `Action` that returns a future of a result, with default content
   */
  final def async(block: R[AnyContent] => Future[Result]): Action[AnyContent] =
    async(BodyParsers.parse.default)(block)

  /**
   * Constructs an `Action` that returns a future of a result, with default content.
   */
  final def async[A](bodyParser: BodyParser[A])(block: R[A] => Future[Result]): Action[A] =
    composeAction(new Action[A] {
      def parser = composeParser(bodyParser)
      def apply(request: Request[A]) = try {
        invokeBlock(StackActionRequest(request), block)
      } catch {
        // NotImplementedError is not caught by NonFatal, wrap it
        case e: NotImplementedError => throw new RuntimeException(e)
        // LinkageError is similarly harmless in Play Framework, since automatic reloading could easily trigger it
        case e: LinkageError => throw new RuntimeException(e)
      }
      override def executionContext = StackActionBuilder.this.executionContext
    })

  /**
   * Compose the parser.  This allows the action builder to potentially intercept requests before they are parsed.
   *
   * @param bodyParser The body parser to compose
   * @return The composed body parser
   */
  protected def composeParser[A](bodyParser: BodyParser[A]): BodyParser[A] = bodyParser

  /**
   * Compose the action with other actions.  This allows mixing in of various actions together.
   *
   * @param action The action to compose
   * @return The composed action
   */
  protected def composeAction[A](action: Action[A]): Action[A] = action

  final def apply(params: Attribute[_]*): StackActionBuilder[R] = new StackActionBuilder[R] {
    def invokeBlock[A](request: StackActionRequest[A], block: R[A] => Future[Result]) = {
      val attrs = new TrieMap[AttributeKey[_], Any] ++= params.map(_.toTuple)
      self.invokeBlock[A](request ++= attrs, block)
    }
    override protected def composeParser[A](bodyParser: BodyParser[A]): BodyParser[A] = self.composeParser(bodyParser)
    override protected def composeAction[A](action: Action[A]): Action[A] = self.composeAction(action)
  }

  /**
   * Compose another ActionFunction with this one, with this one applied last.
   */
  override def andThen[Q[_]](other: ActionFunction[R, Q]): StackActionBuilder[Q] = new StackActionBuilder[Q] {
    def invokeBlock[A](request: StackActionRequest[A], block: Q[A] => Future[Result]) =
      self.invokeBlock[A](request, other.invokeBlock[A](_, block))
    override protected def composeParser[A](bodyParser: BodyParser[A]): BodyParser[A] = self.composeParser(bodyParser)
    override protected def composeAction[A](action: Action[A]): Action[A] = self.composeAction(action)
  }
}

// Statck Action Function
//~~~~~~~~~~~~~~~~~~~~~~~~~
sealed trait StackActionFunctionHelper extends ActionFunction[StackActionRequest, StackActionRequest] with StackActionInjector { self =>
  final def apply(params: Attribute[_]*): ActionFunction[StackActionRequest, StackActionRequest] =
    new ActionFunction[StackActionRequest, StackActionRequest] {
      def invokeBlock[A](request: StackActionRequest[A], block: StackActionRequest[A] => Future[Result]) = {
        val attrs = new TrieMap[AttributeKey[_], Any] ++= params.map(_.toTuple)
        self.invokeBlock[A](request ++= attrs, block)
      }
    }
}

/**
 * A simple kind of ActionFunction which, given a request, may
 * either immediately produce a Result (for example, an error), or call
 * its Action block with a parameter.
 * The critical (abstract) function is refine.
 */
trait StackActionRefiner
    extends ActionRefiner[StackActionRequest, StackActionRequest]
    with StackActionFunctionHelper

/**
 * A simple kind of ActionRefiner which, given a request,
 * unconditionally transforms it to a new parameter type to be passed to
 * its Action block.  The critical (abstract) function is transform.
 */
trait StackActionTransformer
    extends ActionTransformer[StackActionRequest, StackActionRequest]
    with StackActionFunctionHelper

/**
 * A simple kind of ActionRefiner which, given a request, may
 * either immediately produce a Result (for example, an error), or
 * continue its Action block with the same request.
 * The critical (abstract) function is filter.
 */
trait StackActionFilter
    extends ActionRefiner[StackActionRequest, StackActionRequest]
    with StackActionFunctionHelper