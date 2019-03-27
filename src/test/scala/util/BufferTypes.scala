package util

object BufferTypes {

  trait BufferSize { val cap: Long }

  case class FlexibleBuffer private (cap: Long) extends BufferSize
  object FlexibleBuffer {
    def apply(cap: Long): Option[FlexibleBuffer] =
      if (cap >= 0) Some(new FlexibleBuffer(cap)) else None
  }

  case class CappedBuffer private (cap: Long, max: Long) extends BufferSize
  object CappedBuffer {
    def apply(cap: Long, max: Long): Option[CappedBuffer] =
      if (cap <= max && cap >= 0 && max >= 0) Some(new CappedBuffer(cap, max)) else None
  }

  case class LargerBuffer private (cap: Long, min: Long) extends BufferSize
  object LargerBuffer {
    def apply(cap: Long, min: Long): Option[LargerBuffer] =
      if (cap >= min && cap >= 0 && min >= 0) Some(new LargerBuffer(cap, min)) else None
  }

  case class RangeBuffer private (cap: Long, min: Long, max: Long) extends BufferSize
  object RangeBuffer {
    def apply(cap: Long, min: Long, max: Long): Option[RangeBuffer] =
      if (min <= max && cap <= max && cap >= min && cap >= 0 && max >= 0 && min >= 0)
        Some(new RangeBuffer(cap, min, max))
      else
        None
  }

}
