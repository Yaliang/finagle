package com.twitter.finagle.netty4

import com.twitter.io.Buf
import com.twitter.io.Buf.ByteArray
import io.netty.buffer._
import io.netty.util.ByteProcessor

private[finagle] object ByteBufAsBuf {

  object Owned {
    /**
     * Construct a [[Buf]] wrapper for ``ByteBuf``.
     *
     * @note this wrapper does not support ref-counting and therefore should either
     *       be used with unpooled and non-leak detecting allocators or managed
     *       via the ref-counting methods of the wrapped `buf`. Non-empty buffers
     *       are `retain`ed.
     */
    def apply(buf: ByteBuf): Buf =
      if (buf.readableBytes == 0)
        Buf.Empty
      else
        new ByteBufAsBuf(buf)

    /**
     * Extract a [[ByteBufAsBuf]]'s underlying ByteBuf without copying.
     */
    def unapply(wrapped: ByteBufAsBuf): Option[ByteBuf] = Some(wrapped.underlying)

    /**
     * Extract a read-only `ByteBuf` from [[Buf]] potentially without copying.
     */
    def extract(buf: Buf): ByteBuf = BufAsByteBuf.Owned(buf)
  }

  object Shared {
    /**
     * Construct a [[Buf]] by copying `ByteBuf`.
     */
    def apply(buf: ByteBuf): Buf =
      if (buf.readableBytes == 0)
        Buf.Empty
      else
        new ByteBufAsBuf(buf.copy())

    /**
     * Extract a copy of the [[ByteBufAsBuf]]'s underlying ByteBuf.
     */
    def unapply(wrapped: ByteBufAsBuf): Option[ByteBuf] = Some(wrapped.underlying.copy())

    /**
     * Extract a read-only `ByteBuf` copy from [[Buf]].
     */
    def extract(buf: Buf): ByteBuf = BufAsByteBuf.Shared(buf)
  }
}

/**
 * a [[Buf]] wrapper for Netty `ByteBuf`s.
 */
private[finagle] class ByteBufAsBuf(
    private[finagle] val underlying: ByteBuf)
  extends Buf.Indexed {
  // nb: `underlying` is exposed for testing

  private[twitter] def apply(index: Int): Byte =
    underlying.getByte(index)

  private[twitter] def process(processor: Buf.Indexed.Processor): Int = {
    val byteProcessor = new ByteProcessor {
      def process(value: Byte): Boolean = processor(value)
    }
    underlying.forEachByte(byteProcessor)
  }

  def write(bytes: Array[Byte], off: Int): Unit = {
    checkWriteArgs(bytes.length, off)
    val dup = underlying.duplicate()
    dup.readBytes(bytes, off, dup.readableBytes)
  }

  protected def unsafeByteArrayBuf: Option[ByteArray] =
    if (underlying.hasArray) {
      val bytes = underlying.array
      val begin = underlying.arrayOffset + underlying.readerIndex
      val end = begin + underlying.readableBytes
      Some(new Buf.ByteArray(bytes, begin, end))
    } else None

  def length: Int = underlying.readableBytes

  def slice(from: Int, until: Int): Buf = {
    checkSliceArgs(from, until)
    if (isSliceEmpty(from, until)) Buf.Empty
    else if (isSliceIdentity(from, until)) this
    else new ByteBufAsBuf(underlying.slice(from, Math.min(until - from, length - from)))
  }

  override def equals(other: Any): Boolean = other match {
    case ByteBufAsBuf.Owned(otherBB) => underlying.equals(otherBB)
    case composite: Buf.Composite =>
      // Composite.apply has a relatively high overhead, so let it probe
      // back into this Buf.
      composite == this
    case other: Buf if other.length == length =>
      val otherIdx = Buf.Indexed.coerce(other)
      val proc = new ByteProcessor {
        private[this] var pos = 0
        def process(value: Byte): Boolean = {
          if (otherIdx(pos) == value) {
            pos += 1
            true
          } else {
            false
          }
        }
      }
      underlying.forEachByte(proc) == -1

    case _ => false
  }
}
