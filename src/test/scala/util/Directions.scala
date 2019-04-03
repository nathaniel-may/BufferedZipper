package util

object Directions {

  sealed trait NP
  object N extends NP { override def toString: String = "N" }
  object P extends NP { override def toString: String = "P" }

}
