package better_clojure.edn;

import clojure.lang.*;
import java.io.*;
import java.math.*;
import java.util.*;
import java.util.stream.*;

@SuppressWarnings("unchecked")
public final class EDNReader {
  CharReader reader;
  public final boolean throwOnEOF;
  public final Object eofValue;
  public final CharBuffer charBuffer = new CharBuffer();
  public final ILookup dataReaders;
  public final IFn defaultDataReader;
  public final static Keyword TAG_KEY = Keyword.intern(null, "tag");
  public final static Keyword PARAM_TAGS_KEY = Keyword.intern(null, "param-tags");

  public EDNReader(ILookup dataReaders, IFn defaultDataReader, boolean throwOnEOF, Object eofValue) {
    this.dataReaders = dataReaders;
    this.defaultDataReader = defaultDataReader;
    this.throwOnEOF = throwOnEOF;
    this.eofValue = eofValue;
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

  final Object readCharacter() throws Exception {
    int ch = reader.read();
    if (-1 == ch) {
      throw Util.runtimeException("EOF while reading character");
    }
    int peek = reader.read();
    reader.unread();
    if (peek == -1 || CharReader.isBoundary(peek)) {
      return (char) ch;
    } else if ('u' == ch) {
      final char[] temp = tempRead(4);
      ch = Integer.parseInt(new WrappedCharSequence(temp), 0, 4, 16);
      if (ch >= 0xD800 && ch <= 0xDFFF) { // surrogate code unit?
        throw Util.runtimeException("Invalid character constant: \\u" + new String(temp));
      }
      return (char) ch;
    } else if ('o' == ch) {
      ch = 0;
      while (true) {
        peek = reader.read();
        if (peek == -1 || peek < '0' || peek > '7') {
          reader.unread();
          break;
        }
        ch = ch * 8 + Character.digit(peek, 8);
      }
      if (ch > 0377) {
        throw Util.runtimeException("Octal escape sequence must be in range [0, 377]" + context());
      }
      return (char) ch;
    } else if ('n' == ch && "ewline".contentEquals(new WrappedCharSequence(tempRead(6)))) {
      return '\n';
    } else if ('r' == ch && "eturn".contentEquals(new WrappedCharSequence(tempRead(5)))) {
      return '\r';
    } else if ('s' == ch && "pace".contentEquals(new WrappedCharSequence(tempRead(4)))) {
      return ' ';
    } else if ('t' == ch && "ab".contentEquals(new WrappedCharSequence(tempRead(2)))) {
      return '\t';
    } else if ('b' == ch && "ackspace".contentEquals(new WrappedCharSequence(tempRead(8)))) {
      return '\b';
    } else if ('f' == ch && "ormfeed".contentEquals(new WrappedCharSequence(tempRead(7)))) {
      return '\f';
    }
    throw Util.runtimeException("Error parsing character" + context());
  }

  final Object readSymbol(int firstChar, boolean forKeyword) throws Exception {
    boolean usedBuffer = false;
    char[]  buffer     = reader.buffer();
    int     startpos   = reader.position();
    int     pos        = startpos;
    int     len        = buffer.length;
    int     slashPos   = -1;

    if (firstChar >= 0) {
      charBuffer.clear();
      charBuffer.append((char) firstChar);
      usedBuffer = true;
    }

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

    if (end == start) {
      throw Util.runtimeException("Empty " + (forKeyword ? "keyword" : "symbol") + "? How peculiar!");
    }

    if (1 == end - start && chars[start] == '/') {
      return Symbol.intern(null, "/");
    }

    if (!forKeyword && 3 == end - start && chars[start] == 'n' && chars[start + 1] == 'i' && chars[start + 2] == 'l') {
      return null;
    }

    if (!forKeyword && 4 == end - start && chars[start] == 't' && chars[start + 1] == 'r' && chars[start + 2] == 'u' && chars[start + 3] == 'e') {
      return Boolean.TRUE;
    }

    if (!forKeyword && 5 == end - start && chars[start] == 'f' && chars[start + 1] == 'a' && chars[start + 2] == 'l' && chars[start + 3] == 's' && chars[start + 4] == 'e') {
      return Boolean.FALSE;
    }

    if (slashPos == -1) {
      return Symbol.intern(null, new String(chars, start, end - start));
    }

    if (slashPos == 0) {
      throw Util.runtimeException((forKeyword ? "Keyword" : "Symbol") + " namespace can't be empty: " + new String(chars, start, end - start));
    }

    if (slashPos == end - start - 1) {
      throw Util.runtimeException((forKeyword ? "Keyword" : "Symbol") + " name can't be empty: " + new String(chars, start, end - start));
    }

    return Symbol.intern(new String(chars, start, slashPos),
                         new String(chars, start + slashPos + 1, end - (start + slashPos + 1)));
  }

  final Object readKeyword() throws Exception {
    return Keyword.intern((Symbol) readSymbol(-1, true));
  }

  final Number readNumber() throws Exception {
    boolean usedBuffer = false;
    boolean isInt      = false;
    boolean isFloat    = false;
    boolean isRatio    = false;
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
        } else if (nextChar == '/') {
          reader.position(pos + 1);
          isRatio = true;
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

    Number result;

    if (usedBuffer && isFloat) {
      charBuffer.append(buffer, startpos, pos);
      result = finalizeFloat(charBuffer.buffer, 0, charBuffer.len);
    } else if (usedBuffer) {
      charBuffer.append(buffer, startpos, pos);
      result = finalizeInt(charBuffer.buffer, 0, charBuffer.len, radixPos);
    } else if (isFloat) {
      result = finalizeFloat(buffer, startpos, pos);
    } else {
      result = finalizeInt(buffer, startpos, pos, radixPos);
    }

    if (isRatio) {
      result = result instanceof BigInt ? Numbers.reduceBigInt((BigInt) result) : result;
      if (!(result instanceof Long || result instanceof BigInteger)) {
        throw Util.runtimeException("Nominator in ratio can't be " + result.getClass().getName() + ": " + result + context());
      }
      BigInteger nominator = result instanceof Long ? BigInteger.valueOf((Long) result) : (BigInteger) result;
      
      result = readNumber();
      result = result instanceof BigInt ? Numbers.reduceBigInt((BigInt) result) : result;
      if (!(result instanceof Long || result instanceof BigInteger)) {
        throw Util.runtimeException("Denominator in ratio can't be " + result.getClass().getName() + ": " + result + context());
      }
      BigInteger denominator = result instanceof Long ? BigInteger.valueOf((Long) result) : (BigInteger) result;

      result = Numbers.divide(nominator, denominator);
    }

    return result;
  }

  final Number finalizeInt(char[] chars, int start, int end, int radixPos) throws Exception {
    int radix = 10;
    boolean forceBigInt = false;

    if (radixPos >= 0) {
      radix = (int) Long.parseLong(new WrappedCharSequence(chars), start, start + radixPos, 10);
      start = start + radixPos + 1;
    }

    if (chars[end - 1] == 'N') {
      forceBigInt = true;
      end = end - 1;
    }

    if (end == start + 1 && !forceBigInt) {
      return Long.valueOf(Character.digit(chars[start], radix));
    }

    if (chars[start] == '0') {
      if (end - start >= 3 && chars[start + 1] == 'x' || chars[start + 1] == 'X') {
        radix = 16;
        start = start + 2;
      } else if (end - start >= 2) {
        radix = 8;
        start = start + 1;
      }
    }

    if (forceBigInt) {
      final String str = new String(chars, start, end - start);
      BigInteger bn = new BigInteger(str, radix);
      return BigInt.fromBigInteger(bn);
    }

    try {
      return Long.valueOf(Long.parseLong(new WrappedCharSequence(chars), start, end, radix));
    } catch (Exception e) {
      final String str = new String(chars, start, end - start);
      BigInteger bn = new BigInteger(str, radix);
      return BigInt.fromBigInteger(bn);
    }
  }

  final Number finalizeFloat(char[] chars, int start, int end) throws Exception {
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

  public final Object readMeta() throws Exception {
    Object meta = readObject(true);
    if (meta instanceof Symbol || meta instanceof String) {
      meta = RT.map(TAG_KEY, meta);
    } else if (meta instanceof IPersistentVector) {
      meta = RT.map(PARAM_TAGS_KEY, meta);
    } else if (meta instanceof Keyword) {
      meta = RT.map(meta, RT.T);
    } else if (!(meta instanceof IPersistentMap)) {
      throw new IllegalArgumentException("Metadata must be Symbol, Keyword, String, Map or Vector");
    }

    Object value = readObject(true);
    if (!(value instanceof IMeta)) {
      throw new IllegalArgumentException("Can't put meta on " + (value == null ? "null" : value.getClass()) + ": " + value + context());
    }
    IPersistentMap ometa = (IPersistentMap) RT.meta(value);
    meta = ((IKVReduce) meta).kvreduce(new AFn() {
      @Override
      public Object invoke(Object ometa, Object key, Object value) {
        return RT.assoc(ometa, key, value);
      }
    }, ometa);
    return ((IObj) value).withMeta((IPersistentMap) meta);
  }

  public final Object readObject() throws Exception {
    return readObject(throwOnEOF);
  }

  public final Object readObject(boolean throwOnEOF) throws Exception {
    if (reader == null)
      return null;

    while (true) {
      int val = reader.eatwhite();
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
      case '-': {
        val = reader.read();
        if (-1 == val || CharReader.isBoundary(val)) {
          return Symbol.intern(null, "-");
        }
        if (CharReader.isDigit(val)) {
          reader.unread();
          Object res = readNumber();
          if (res instanceof Long) {
            return Long.valueOf(-((Long) res).longValue());
          } else if (res instanceof Double) {
            return Double.valueOf(-((Double) res).doubleValue());
          } else if (res instanceof BigInt) {
            return BigInt.fromBigInteger(((BigInt) res).toBigInteger().negate());
          } else if (res instanceof BigDecimal) {
            return ((BigDecimal) res).negate();
          }
        } else {
          reader.unread();
          return readSymbol('-', false);
        }
      }
      case '^': {
        return readMeta();
      }
      case '\\': {
        return readCharacter();
      }
      case '+': {
        val = reader.read();
        if (-1 == val || CharReader.isBoundary(val)) {
          return Symbol.intern(null, "+");
        }
        if (CharReader.isDigit(val)) {
          reader.unread();
          return readNumber();
        } else {
          reader.unread();
          return readSymbol('+', false);
        }
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

        // Tagged Literals
        reader.unread();
        Object tag = readObject(true);
        if (tag instanceof Symbol) {
          Object value = readObject(true);
          IFn dataReader = (IFn) RT.get(dataReaders, tag);
          if (dataReader != null) {
            return dataReader.invoke(value);
          } else if (defaultDataReader != null) {
            return defaultDataReader.invoke(tag, value);
          }
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
        if (CharReader.isNumberChar(val)) {
          reader.unread();
          return readNumber();
        } else if (!CharReader.isBoundary(val)) {
          reader.unread();
          return readSymbol(-1, false);
        }
        throw new Exception("Parse error - Unexpected character - " + val);
      }
    }
  }

  public void beginParse(CharReader rdr) {
    reader = rdr;
  }
}
