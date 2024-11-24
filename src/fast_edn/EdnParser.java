package fast_edn;

import clojure.lang.*;
import java.io.*;
import java.util.*;
import java.util.function.*;

@SuppressWarnings("unchecked")
public class EdnParser {
  public static final int CAPACITY = 8192;
  public Reader rdr;
  public final char[] buf;
  public int len;
  public int pos;

  public char[] collectBuf = null;
  
  public EdnParser(Reader rdr, int capacity) {
    this.rdr = rdr;
    buf = new char[capacity];
    len = 0;
    pos = 0;
    try {
      len = rdr.read(buf, 0, buf.length);
    } catch (IOException e) {
      Util.sneakyThrow(e);
    }
  }

  public EdnParser(Reader rdr) {
    this(rdr, CAPACITY);
  }

  public EdnParser(String str) {
    this(new StringReader(str), CAPACITY);
  }

  public void nextBuf() {
    assert pos == len;
    try {
      // load next buf
      len = rdr.read(buf, 0, buf.length);
      pos = 0;
    } catch (IOException e) {
      Util.sneakyThrow(e);
    }
  }

  public int read() {
    if (pos < len) {
      return buf[pos++];
    }

    if (pos == len) {
      nextBuf();
      return len > 0 ? buf[pos++] : -1;
    }

    return -1;
  }

  public void unread() {
    assert pos > 0;
    pos -= 1;
  }

  public final String context() {
    int start = Math.max(0, pos - 100);
    int end = Math.min(len, pos + 100);
    return ", context:\n" + new String(buf, start, end - start);
  }

  public int skipWhile(IntPredicate pred) {
    while (true) {
      int ch = pos < len ? buf[pos++] : read();
      if (ch == -1) {
        return -1;
      }
      boolean check = pred.test(ch);
      if (!check) {
        return ch;
      }
    }
  }

  public char[] ensureCollectCapacity(int collectLen, int toCopy) {
    if (collectBuf == null) {
      assert collectLen == 0;
      collectBuf = new char[Integer.highestOneBit(toCopy) << 1];
    } else if (collectBuf.length < collectLen + toCopy) {
      int newLen = collectBuf.length * 2;
      while (newLen < collectLen + toCopy) {
        newLen = newLen * 2;
      }
      char[] newBuf = new char[newLen];
      System.arraycopy(collectBuf, 0, newBuf, 0, collectLen);
      collectBuf = newBuf;
    }
    return collectBuf;
  }

  public int collect(int collectLen, int from, int to) {
    int toCopy = to - from;
    // assert toCopy >= 0;
    if (toCopy == 0) {
      return collectLen;
    }
    ensureCollectCapacity(collectLen, toCopy);
    System.arraycopy(buf, from, collectBuf, collectLen, toCopy);
    return collectLen + toCopy;
  }

  // public void collect(char ch) {
  //   ensureCollectCapacity(1);
  //   collectBuf[collectLen++] = ch;
  // }

  public CharSeq collectFinish(int collectLen, int from, int to) {
    if (collectLen == 0) {
      return new CharSeq(buf, from, to);
    } else {
      collectLen = collect(collectLen, from, to);
      return new CharSeq(collectBuf, 0, collectLen);
    }
  }

  public int digit(int ch, int radix) {
    int digit = Character.digit(ch, radix);
    if (digit == -1) {
      throw new RuntimeException("Unexpected digit: " + ch + " (" + (char) ch + ")" + context());
    }
    return digit;
  }

  public Object readString() {
    int _collectStart = pos;
    int _collectLen = 0;
    int _pos = pos;

    outer:
    while (len > 0) {
      for (; _pos < len;) {
        final char nextChar = buf[_pos++];
        // System.out.println("pos: " + pos + ", next: " + nextChar);
        if ('"' == nextChar) {
          break outer;
        }
        /* if ('\\' == nextChar) {
          collectSoFar(-1);
          int nnextChar = pos < len ? buf[pos++] : read(false);
          // System.out.println("collectStart: " + collectStart + ", collectLen: " + collectLen);
          // System.out.println("pos: " + pos + ", next: " + nextChar + ", nnext: " + nnextChar + ", coll: " + new String(collectBuf, 0, collectLen));
          if (-1 == nnextChar) {
            throw new RuntimeException("EOF while reading string: \"" + collectFinish().toString() + context());
          }
          if ('u' == nnextChar) {
            // System.out.println("Uni" + nnextChar + " " + len);
            if (pos + 4 < len) {
              nnextChar = (digit(buf[pos], 16) << 12)
                        | (digit(buf[pos + 1], 16) << 8)
                        | (digit(buf[pos + 2], 16) << 4)
                        | digit(buf[pos + 3], 16);
              pos += 4;
            } else {
              nnextChar = 0;
              for (int i = 0; i < 4; ++i) {
                int digit = read(false);
                // nnextChar = (nnextChar << 4) + digit(digit, 16);
              }
            }
          }
          collectAppend((char) nnextChar);
          collectStart = pos;
          continue;
        }*/
      }
      _collectLen = collect(_collectLen, _collectStart, _pos);

      nextBuf();
      _collectStart = pos;
      _pos = pos;
    }
    pos = _pos;

    return collectFinish(_collectLen, _collectStart, _pos - 1).toString();
  }

  public static boolean isWhitespace(int ch) {
    if (ch >= '\u0021' && ch <= '\u002B') return false;
    if (ch >= '\u002D' && ch < '\u2000') return false;
    if (ch == ' ' || ch == '\n' || ch == ',' || ch == '\t' || ch == '\r') return true;
    return Character.isWhitespace(ch);
  }

  public Object readVector() {
    ITransientCollection acc = PersistentVector.EMPTY.asTransient();
    while (true) {
      final int nextChar = skipWhile(EdnParser::isWhitespace);
      if (nextChar == ']') {
        return acc.persistent();
      } else if (nextChar == -1) {
        throw new RuntimeException("EOF while reading vector: " + String.valueOf(acc.persistent()) + context());
      } else {
        unread();
        acc = acc.conj(readObject());
      }
    }
  }

  public Object readObject() {
    final int nextChar = skipWhile(EdnParser::isWhitespace);
    if ('"' == nextChar) {
      return readString();
    } else if ('[' == nextChar) {
      return readVector();
    } else if (-1 == nextChar) {
      throw new RuntimeException("EOF while reading Object" + context());
    } else {
      throw new RuntimeException("Unexpected char: " + ((char) nextChar) + context());
    }
  }
}