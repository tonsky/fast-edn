package better_clojure.edn;

import clojure.lang.ATransientMap;
import clojure.lang.ATransientSet;
import clojure.lang.BigInt;
import clojure.lang.IPersistentList;
import clojure.lang.ITransientCollection;
import clojure.lang.Keyword;
import clojure.lang.LazilyPersistentVector;
import clojure.lang.Numbers;
import clojure.lang.PersistentArrayMap;
import clojure.lang.PersistentHashMap;
import clojure.lang.PersistentHashSet;
import clojure.lang.PersistentList;
import clojure.lang.PersistentVector;
import clojure.lang.Symbol;
import clojure.lang.Util;
import java.io.EOFException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.ListIterator;
import java.util.stream.Collectors;

@SuppressWarnings("unchecked")
public final class EDNReader {
  CharReader reader;
  public final boolean throwOnEOF;
  public final Object eofValue;
  public final CharBuffer charBuffer = new CharBuffer();

  public EDNReader(boolean _throwOnEOF, Object _eofValue) {
    throwOnEOF = _throwOnEOF;
    eofValue = _eofValue;
  }

  final char[] tempRead(int nchars) throws EOFException {
    // TODO share buf
    final char[] tempBuf = new char[nchars];
    if (reader.read(tempBuf, 0, nchars) == -1)
      throw new EOFException();
    return tempBuf;
  }

  final Object readString() throws Exception {
    charBuffer.clear();
    char[] buffer = reader.buffer();
    while (buffer != null) {
      int startpos = reader.position();
      int len = buffer.length;
      for (int pos = startpos; pos < len; ++pos) {
        final char curChar = buffer[pos];
        if (curChar == '"') {
          final Object rv = charBuffer.toString(buffer, startpos, pos);
          reader.position(pos + 1);
          return rv;
        } else if (curChar == '\\') {
          charBuffer.append(buffer, startpos, pos);
          final int idata = reader.readFrom(pos + 1);
          if (idata == -1)
            throw new EOFException();
          final char data = (char) idata;
          switch (data) {
            case '"':
            case '\\':
            case '/':
              charBuffer.append(data);
              break;
            case 'b':
              charBuffer.append('\b');
              break;
            case 'f':
              charBuffer.append('\f');
              break;
            case 'r':
              charBuffer.append('\r');
              break;
            case 'n':
              charBuffer.append('\n');
              break;
            case 't':
              charBuffer.append('\t');
              break;
            case 'u':
              final char[] temp = tempRead(4);
              charBuffer.append((char) (Integer.parseInt(new String(temp, 0, 4), 16)));
              break;
            default:
              throw new Exception("Parse error - Unrecognized escape character: " + data);
          }
          buffer = reader.buffer();
          startpos = reader.position();
          // pos will be incremented in loop update
          pos = startpos - 1;
        }
      }
      charBuffer.append(buffer, startpos, len);
      buffer = reader.nextBuffer();
    }
    throw new EOFException("Parse error - EOF while reading string: " + charBuffer.toString());
  }

  final Object readSymbol() throws Exception {
    boolean usedBuffer = false;
    char[]  buffer     = reader.buffer();
    int     startpos   = reader.position();
    int     pos        = startpos;
    int     len        = buffer.length;
    int     slashPos   = -1;

    outer:
    while (buffer != null) {
      for (; pos < len; ++pos) {
        final char nextChar = buffer[pos];
        if (slashPos == -1 && '/' == nextChar) {
          slashPos = (usedBuffer ? charBuffer.length() : 0) + pos - startpos;
        } else if (CharReader.isBoundary(nextChar)) {
          reader.position(pos);
          break outer;
        }
      }
      char[] nextBuffer = reader.nextBuffer();
      if (nextBuffer == null) {
        break;
      }
      if (!usedBuffer) {
        usedBuffer = true;
        charBuffer.clear();
      }
      charBuffer.append(buffer, startpos, pos);
      buffer   = nextBuffer;
      startpos = reader.position();
      pos      = startpos;
      len      = buffer.length;
    }

    char[] chars;
    int start, end;
    if (usedBuffer) {
      charBuffer.append(buffer, startpos, pos);
      chars = charBuffer.buffer;
      start = 0;
      end = charBuffer.len;
    } else {
      chars = buffer;
      start = startpos;
      end = pos;
    }

    if (3 == end - start && chars[start] == 'n' && chars[start + 1] == 'i' && chars[start + 2] == 'l') {
      return null;
    }

    if (4 == end - start && chars[start] == 't' && chars[start + 1] == 'r' && chars[start + 2] == 'u' && chars[start + 3] == 'e') {
      return Boolean.TRUE;
    }

    if (5 == end - start && chars[start] == 'f' && chars[start + 1] == 'a' && chars[start + 2] == 'l' && chars[start + 3] == 's' && chars[start + 4] == 'e') {
      return Boolean.FALSE;
    }

    if (slashPos < 0) {
      return Symbol.intern(null, new String(chars, start, end - start));
    }

    return Symbol.intern(new String(chars, start, slashPos - start), new String(chars, start + slashPos + 1, end - (start + slashPos + 1)));
  }

  final Object readKeyword() throws Exception {
    boolean usedBuffer = false;
    char[]  buffer     = reader.buffer();
    int     startpos   = reader.position();
    int     pos        = startpos;
    int     len        = buffer.length;
    int     slashPos   = -1;

    outer:
    while (buffer != null) {
      for (; pos < len; ++pos) {
        final char nextChar = buffer[pos];
        if (slashPos == -1 && '/' == nextChar) {
          slashPos = (usedBuffer ? charBuffer.length() : 0) + pos - startpos;
        } else if (CharReader.isBoundary(nextChar)) {
          reader.position(pos);
          break outer;
        }
      }
      char[] nextBuffer = reader.nextBuffer();
      if (nextBuffer == null) {
        break;
      }
      if (!usedBuffer) {
        usedBuffer = true;
        charBuffer.clear();
      }
      charBuffer.append(buffer, startpos, pos);
      buffer   = nextBuffer;
      startpos = reader.position();
      pos      = startpos;
      len      = buffer.length;
    }

    char[] chars;
    int start, end;
    if (usedBuffer) {
      charBuffer.append(buffer, startpos, pos);
      chars = charBuffer.buffer;
      start = 0;
      end = charBuffer.len;
    } else {
      chars = buffer;
      start = startpos;
      end = pos;
    }

    return slashPos < 0
      ? Keyword.intern(null, new String(chars, start, end - start))
      : Keyword.intern(new String(chars, start, slashPos), new String(chars, start + slashPos + 1, end - (start + slashPos + 1)));
  }

  final Object readNumber() throws Exception {
    boolean usedBuffer = false;
    boolean isInt      = false;
    boolean isFloat    = false;
    char[]  buffer     = reader.buffer();
    int     startpos   = reader.position();
    int     pos        = startpos;
    int     len        = buffer.length;
    int     radixPos   = -1;
    outer:
    while (buffer != null) {
      for (; pos < len; ++pos) {
        final char nextChar = buffer[pos];
        if (CharReader.isDigit(nextChar)) {
          continue;
        } else if (CharReader.isBoundary(nextChar)) {
          reader.position(pos);
          break outer;
        } else if (!isInt && !isFloat && (nextChar == '.' || nextChar == 'e' || nextChar == 'E' || nextChar == 'M')) {
          isFloat = true;
        } else if (!isInt && !isFloat && (nextChar == 'x' || nextChar == 'X' || nextChar == 'N')) {
          isInt = true;
        } else if (radixPos == -1 && (nextChar == 'r' || nextChar == 'R')) {
          radixPos = (usedBuffer ? charBuffer.length() : 0) + pos - startpos;
          isInt = true;
        }
      }
      char[] nextBuffer = reader.nextBuffer();
      if (nextBuffer == null) {
        break;
      }
      if (!usedBuffer) {
        usedBuffer = true;
        charBuffer.clear();
      }
      charBuffer.append(buffer, startpos, pos);
      buffer   = nextBuffer;
      startpos = reader.position();
      pos      = startpos;
      len      = buffer.length;
    }

    if (usedBuffer && isFloat) {
      charBuffer.append(buffer, startpos, pos);
      return finalizeFloat(charBuffer.buffer, 0, charBuffer.len);
    } else if (usedBuffer) {
      charBuffer.append(buffer, startpos, pos);
      return finalizeInt(charBuffer.buffer, 0, charBuffer.len, radixPos);
    } else if (isFloat) {
      return finalizeFloat(buffer, startpos, pos);
    } else {
      return finalizeInt(buffer, startpos, pos, radixPos);
    }
  }

  public Long parseLong(char[] chars, int start, int end, int radix) {
    return Long.valueOf(Long.parseLong(new WrappedCharSequence(chars), start, end, radix));
  }

  final Object finalizeInt(char[] chars, int start, int end, int radixPos) throws Exception {
    final int len = end - start;
    if (len == 1) {
      return Long.valueOf(Character.digit(chars[start], 10));
    }

    if (chars[start] == '-') {
      Object res = finalizeInt(chars, start + 1, end, radixPos - 1);
      if (res instanceof Long) {
        return Long.valueOf(-((Long) res).longValue());
      } else {
        return BigInt.fromBigInteger(((BigInt) res).toBigInteger().negate());
      }
    }

    if (chars[start] == '+') {
      return finalizeInt(chars, start + 1, end, radixPos - 1);
    }

    if (radixPos >= 0) {
      int radix = parseLong(chars, start, start + radixPos, 10).intValue();
      return parseLong(chars, start + radixPos + 1, end, radix);
    }

    if (chars[start] == '0') {
      if (len < 2) {
        throw Util.runtimeException("Invalid number: " + new String(chars, start, len));
      }

      if (chars[start + 1] == 'x' || chars[start + 1] == 'X') {
        return parseLong(chars, start + 2, end, 16);
      }

      return parseLong(chars, start + 1, end, 8);
    }

    if (chars[end - 1] == 'N') {
      final String str = new String(chars, start, len - 1);
      BigInteger bn = new BigInteger(str);
      return BigInt.fromBigInteger(bn);
    }

    if (len <= 18) {
      return parseLong(chars, start, end, 10);
    }

    final String str = new String(chars, start, len);
    BigInteger bn = new BigInteger(str);
    return bn.bitLength() < 64 ? Numbers.num(bn.longValue()) : BigInt.fromBigInteger(bn);
  }

  final Object finalizeFloat(char[] chars, int start, int end) throws Exception {
    final int len = end - start;
    if (chars[end - 1] == 'M') {
      return new BigDecimal(chars, start, len - 1);
    } else {
      return Double.parseDouble(new String(chars, start, len));
    }
  }

  public final String context() throws Exception {
    String context = reader.context(200);
    if (context != null) {
      return ", context:\n" + context;
    } else {
      return "";
    }
  }

  public final Object readList() throws Exception {
    ArrayList acc = new ArrayList();
    while (!reader.eof()) {
      final char nextChar = reader.eatwhite();
      if (nextChar == ')') {
        IPersistentList res = PersistentList.EMPTY;
        for(ListIterator i = acc.listIterator(acc.size()); i.hasPrevious(); ) {
          res = (IPersistentList) res.cons(i.previous());
        }
        return res;
      } else if (nextChar == 0) {
        break;
      } else {
        reader.unread();
        acc.add(readObject());
      }
    }
    throw Util.runtimeException("EOF while reading list: " + acc.stream().map(String::valueOf).collect(Collectors.joining(" ", "(", ")")) + context());
  }

  public final Object readVector() throws Exception {
    ITransientCollection acc = PersistentVector.EMPTY.asTransient();
    while (!reader.eof()) {
      final char nextChar = reader.eatwhite();
      if (nextChar == ']') {
        return acc.persistent();
      } else if (nextChar == 0) {
        break;
      } else {
        reader.unread();
        acc = acc.conj(readObject());
      }
    }
    throw Util.runtimeException("EOF while reading vector: " + String.valueOf(acc.persistent()) + context());
  }

  public final Object readSet() throws Exception {
    ATransientSet acc = (ATransientSet) PersistentHashSet.EMPTY.asTransient();
    int count = 0;
    while (!reader.eof()) {
      final char nextChar = reader.eatwhite();
      if (nextChar == '}') {
        return acc.persistent();
      } else if (nextChar == 0) {
        break;
      } else {
        reader.unread();
        Object key = readObject();
        acc = (ATransientSet) acc.conj(key);
        int newCount = acc.count();
        if (count + 1 != newCount) {
          throw new IllegalArgumentException("Duplicate key: " + key + " reading set: " + String.valueOf(acc.persistent()) + context());
        }
        count = newCount;
      }
    }
    throw Util.runtimeException("EOF while reading set: " + String.valueOf(acc.persistent()) + context());
  }

  public final Object readMap() throws Exception {
    ATransientMap acc = (ATransientMap) PersistentArrayMap.EMPTY.asTransient();
    while (!reader.eof()) {
      char nextChar = reader.eatwhite();
      if (nextChar == '}') {
        return acc.persistent();
      } else if (nextChar == 0) {
        break;
      } else {
        reader.unread();
        // read key
        Object key = readObject();
        if (acc.containsKey(key)) {
          throw new IllegalArgumentException("Duplicate key: " + key + " reading map: " + String.valueOf(acc.persistent()) + context());
        }

        nextChar = reader.eatwhite();
        if (nextChar == '}') {
          throw Util.runtimeException("Map literal must contain an even number of forms: " + String.valueOf(acc.persistent()) + context());
        }
        reader.unread();

        // read value
        Object val = readObject();
        acc = (ATransientMap) acc.assoc(key, val);
      }
    }
    throw Util.runtimeException("EOF while reading map: " + String.valueOf(acc.persistent()) + context());
  }

  public final Object readObject() throws Exception {
    return readObject(throwOnEOF);
  }

  public final Object readObject(boolean throwOnEOF) throws Exception {
    if (reader == null)
      return null;

    while (true) {
      char val = reader.eatwhite();
      switch (val) {
      case '"':
        return readString();
      case ':':
        return readKeyword();
      case '{': {
        return readMap();
      }
      case '[': {
        return readVector();
      }
      case '(': {
        return readList();
      }
      case ';': {
        reader.eat(ch -> '\n' != ch && '\r' != ch);
        continue;
      }
      case '#': {
        final int nextChar = reader.read();
        if (nextChar == -1) {
          throw Util.runtimeException("EOF while reading dispatch macro");
        }
        if (nextChar == '{') {
          return readSet();
        }
        if (nextChar == '_') {
          readObject(true);
          continue;
        }
        if (nextChar == '#') {
          final char[] data = tempRead(3);
          if (data[0] == 'I' && data[1] == 'n' && data[2] == 'f')
            return Double.POSITIVE_INFINITY;
          if (data[0] == '-' && data[1] == 'I' && data[2] == 'n' && reader.read() == 'f') {
            return Double.NEGATIVE_INFINITY;
          }
          if (data[0] == 'N' && data[1] == 'a' && data[2] == 'N') {
            return Double.NaN;
          }
          throw Util.runtimeException("Unknown symbolic value: ##" + new String(data));
        }
        throw Util.runtimeException("No dispatch macro for: #" + Character.toString(nextChar));
      }
      case 0:
        if (reader.eof()) {
          if (throwOnEOF) {
            throw Util.runtimeException("EOF while reading");
          } else {
            return eofValue;
          }
        }
        // fallthrough intentional
      default:
        // TODO symbols starting with -/+
        if (CharReader.isNumberChar(val)) {
          reader.unread();
          return readNumber();
        } else if (!CharReader.isBoundary(val)) {
          reader.unread();
          return readSymbol();
        }
        throw new Exception("Parse error - Unexpected character - " + val);
      }
    }
  }

  public void beginParse(CharReader rdr) {
    reader = rdr;
  }
}
