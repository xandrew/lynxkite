package com.lynxanalytics.biggraph.graph_api

import scala.language.higherKinds
import scala.reflect.runtime.universe._
import scala.reflect.ClassTag

trait RuntimeSafeCastable[T, ConcreteKind[T] <: RuntimeSafeCastable[T, ConcreteKind]] {
  implicit val typeTag: TypeTag[T]

  def runtimeSafeCast[S: TypeTag]: ConcreteKind[S] = {
    if (typeOf[S] =:= typeOf[T]) {
      this.asInstanceOf[ConcreteKind[S]]
    } else throw new ClassCastException("Cannot cast from: %s to: %s".format(typeOf[S], typeOf[T]))
  }

  def classTag: ClassTag[T] = {
    ClassTag[T](typeTag.mirror.runtimeClass(typeTag.tpe))
  }
}

