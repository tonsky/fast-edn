package better_clojure.edn;

import java.util.function.IntPredicate;
import java.util.function.Supplier;

public final class CharReader {
  Supplier<char[]> buffers;
  char[] curBuffer;
  int curPos;

  public static class SingletonData implements Supplier<char[]> {
    public char[] data;
    public SingletonData(char[] _d) {
      data = _d;
    }
    public char[] get() {
      char[] retval = data;
      data = null;
      return retval;
    }
  }

  public static class BufferSupplier implements Supplier<char[]> {
    public static final int capacity = 8192;
    public final String s;
    public int pos = 0;
    public final int len;
    public char[] buf;

    public BufferSupplier(String s) {
      this.s = s;
      this.len = s.length();
    }

    public char[] get() {
      if (pos + capacity < len) {
        if (buf == null) {
          buf = new char[capacity];
        }
        s.getChars(pos, pos + capacity, buf, 0);
        pos += capacity;
        return buf;
      }

      if (pos == len) {
        return null;
      }

      buf = new char[len - pos];
      s.getChars(pos, len, buf, 0);
      pos = len;
      return buf;
    }
  }

  public CharReader(Supplier<char[]> _buf) {
    buffers = _buf;
    nextBuffer();
  }

  public CharReader(char[] data) {
    this(new SingletonData(data));
  }

  public CharReader(String data) {
    this(new BufferSupplier(data));
    // this(new SingletonData(data.toCharArray()));
  }

  public final char[] buffer() {
    return curBuffer;
  }
  public final int position() {
    return curPos;
  }
  public final void position(int pos) {
    curPos = pos;
  }
  public final int bufferLength() {
    return curBuffer != null ? curBuffer.length : 0;
  }

  public final int remaining() {
    return bufferLength() - curPos;
  }

  public static boolean isWhitespace(int ch) {
    if (ch >= '\u0021' && ch <= '\u002B') return false;
    if (ch >= '\u002D' && ch < '\u2000') return false;
    if (ch == ' ' || ch == '\n' || ch == ',' || ch == '\t' || ch == '\r') return true;
    return Character.isWhitespace(ch);
  }

  public static boolean isBoundary(int ch) {
    return isWhitespace(ch) || ch == ']' || ch == '}' || ch == ')' || ch == '[' || ch == '{' || ch == '(' || ch == '\\' || ch == '"';
  }

  public static boolean isNumberChar(int v) {
    return (v >= '0' && v <= '9') || v == '-' || v == '+';
  }

  public static boolean isDigit(int v) {
    return v >= '0' && v <= '9';
  }

  public final char eat(IntPredicate pred) {
    char[] buffer = curBuffer;
    while (buffer != null) {
      final int len = buffer.length;
      int pos = curPos;
      for (; pos < len && pred.test(buffer[pos]); ++pos);
      if (pos < len) {
        final char retval = buffer[pos];
        position(pos + 1);
        return retval;
      }
      buffer = nextBuffer();
    }
    return 0;
  }

  public final char eatwhite() {
    return eat(CharReader::isWhitespace);
  }

  public boolean eof() {
    return curBuffer == null;
  }

  public final char[] nextBuffer() {
    char[] nextbuf;
    if (buffers != null)
      nextbuf = buffers.get();
    else
      nextbuf = null;

    if (nextbuf != null) {
      curBuffer = nextbuf;
      curPos = 0;
    } else {
      curBuffer = null;
      curPos = -1;
    }
    return nextbuf;
  }

  public final int read() {
    // common case
    if (remaining() > 0) {
      return curBuffer[curPos++];
    } else {
      nextBuffer();
      if (eof())
        return -1;
      return read();
    }
  }

  public final int read(char[] buffer, int off, int len) {
    // common case first
    int leftover = len;
    char[] srcBuf = curBuffer;
    while (leftover > 0 && srcBuf != null) {
      final int pos = curPos;
      final int rem = srcBuf.length - pos;
      int readlen = Math.min(rem, leftover);
      System.arraycopy(srcBuf, pos, buffer, off, readlen);
      off += readlen;
      leftover -= readlen;
      if (leftover == 0) {
        curPos = pos + readlen;
        return len;
      }
      srcBuf = nextBuffer();
    }
    return -1;
  }

  public final void unread() {
    --curPos;
    // Unread only works until you reach the beginning of the current buffer.  If you just
    // loaded a new buffer and read a char and then have to unread more than one character
    // then we need to allocate a new buffer and reset curPos and such.  Will implement
    // if needed.
    if (curPos < 0) {
      if (eof())
        curPos = 0;
      else
        throw new RuntimeException("Unread too far - current buffer empty");
    }
  }

  final int readFrom(int pos) {
    curPos = pos;
    return read();
  }

  // Reads anything possible around the area.
  public final String context(int nChars) throws Exception {
    final char[] buf = buffer();
    if (buf != null) {
      final int pos = position();
      final int len = buf.length;
      int startpos = Math.max(0, pos - nChars);
      int endpos = Math.min(len - 1, pos + nChars);
      return new String(buf, startpos, endpos - startpos);
    } else {
      return null;
    }
  }
}
