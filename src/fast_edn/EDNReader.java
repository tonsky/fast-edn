package fast_edn;

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


  /////////////////
  // Accumulator //
  /////////////////

  private char[] accumulator = new char[32];
  private int accumulatorLength = 0;

  private void accumulatorEnsureCapacity(int len) {
    if (len > accumulator.length) {
      accumulator = Arrays.copyOf(accumulator, Integer.highestOneBit(len) << 1);
    }
  }

  private void accumulatorAppend(char ch) {
    // TODO if accumulatorLength + 1 <= accumulator.length
    accumulatorEnsureCapacity(accumulatorLength + 1);
    accumulator[accumulatorLength] = ch;
    accumulatorLength += 1;
  }

  private void accumulatorAppend(char[] data, int start, int end) {
    int len = end - start;
    if (len > 0) {
      accumulatorEnsureCapacity(accumulatorLength + len);
      // TODO if (len < 5)
      System.arraycopy(data, start, accumulator, accumulatorLength, len);
      accumulatorLength += len;
    }
  }

  private CharSeq accumulatorToCharSeq() {
    return new CharSeq(accumulator, 0, accumulatorLength);
  }

  private String accumulatorToString() {
    return new String(accumulator, 0, accumulatorLength);
  }


  ////////////////
  // readString //
  ////////////////

  public String readString() {
    char[] buf   = reader.buffer();
    int    start = reader.position();
    int    pos   = start;
    int    len   = buf.length;
    for (; pos < len; ++pos) {
      char ch = buf[pos];
      if (ch == '"') {
        reader.position(pos + 1);
        return new String(buf, start, pos - start);
      }
      if (ch == '\\') {
        break;
      }
    }
    reader.position(pos);
    return readStringComplex(buf, start, pos);
  }

  public byte digit16(int ch) {
    if ('0' <= ch && ch <= '9') {
      return (byte) (ch - '0');
    }
    
    if ('a' <= ch && ch <= 'f') {
      return (byte) (ch - 'a' + 10);
    }
    
    if ('A' <= ch && ch <= 'F') {
      return (byte) (ch - 'A' + 10);
    }
    
    if (-1 == ch) {
      throw new RuntimeException("EOF while reading" + context());
    }

    throw new RuntimeException("Unexpected digit: " + ch + context());
  }

  public char readUnicodeChar() {
    char[] buf = reader.buffer();
    int pos = reader.position();
    if (pos + 4 < reader.bufferLength()) {
      int ch = (digit16(buf[pos    ]) << 12)
             + (digit16(buf[pos + 1]) << 8)
             + (digit16(buf[pos + 2]) << 4)
             +  digit16(buf[pos + 3]);
      reader.position(pos + 4);
      return (char) ch;
    }

    int ch = (digit16(reader.read()) << 12)
           + (digit16(reader.read()) << 8)
           + (digit16(reader.read()) << 4)
           +  digit16(reader.read());
    return (char) ch;
  }

  public char readOctalChar() {
    int value = 0;
    for (int i = 0; i < 3; ++i) {
      int ch = reader.read();
      if (ch >= '0' && ch <= '7') {
        value = (value << 3) + (ch - '0');
      } else {
        reader.unread();
        break;
      }
    }

    if (value > 0377) {
      throw Util.runtimeException("Octal escape sequence must be in range [0, 377], got: " + value + context());
    }
    
    return (char) value;
  }

  public String readStringComplex(char[] buf, int start, int pos) {
    accumulatorLength = 0;
    accumulatorAppend(buf, start, pos);

    outer:
    while (true) {
      buf = reader.buffer();
      if (buf == null) {
        throw new RuntimeException("EOF while reading string: " + accumulatorToString() + context());
      }
      start = reader.position();
      pos = start;
      int len = reader.bufferLength();

      for (; pos < len; ++pos) {
        char ch = buf[pos];

        if (ch == '\\') {
          accumulatorAppend(buf, start, pos);
          int ch2 = reader.readFrom(pos + 1);

          if (ch2 == '"') {
            accumulatorAppend('"');
          } else if (ch2 == '\\') {
            accumulatorAppend('\\');
          } else if (ch2 == 'n') {
            accumulatorAppend('\n');
          } else if (ch2 == 'r') {
            accumulatorAppend('\r');
          } else if (ch2 == 'u') {
            accumulatorAppend(readUnicodeChar());
          } else if (ch2 == 't') {
            accumulatorAppend('\t');
          } else if (ch2 >= '0' && ch2 <= '7') {
            reader.unread();
            accumulatorAppend(readOctalChar());
          } else if (ch2 == 'b') {
            accumulatorAppend('\b');
          } else if (ch2 == 'f') {
            accumulatorAppend('\f');
          } else if (ch2 == -1) {
            throw new RuntimeException("EOF reading string: " + context());
          } else {
            throw new RuntimeException("Unrecognized escape character while reading string: " + ((char) ch2) + context());
          }

          continue outer;
        } else if (ch == '"') {
          reader.position(pos + 1);
          accumulatorAppend(buf, start, pos);
          return accumulatorToString();
        }
      }

      accumulatorAppend(buf, start, len);
      reader.nextBuffer();
    }
  }


  ///////////////////
  // readCharacter //
  ///////////////////

  private boolean compareNext(int ch, String s) {
    if ((char) ch != s.charAt(0)) {
      return false;
    }

    for (int i = 1; i < s.length(); ++i) {
      ch = reader.read();
      if (ch == -1) {
        throw new RuntimeException("EOF while reading" + context());
      }
      if ((char) ch != s.charAt(i)) {
        return false;
      }
    }
    return true;
  }
  
  public Character readCharacter() {
    int ch = reader.read();
    
    if (-1 == ch) {
      throw new RuntimeException("EOF while reading character" + context());
    }

    int peek = reader.read();
    reader.unread();

    if (peek == -1 || CharReader.isBoundary(peek)) {
      return (char) ch;
    }

    if (ch == 'u') {
      ch = readUnicodeChar();
      // surrogate code unit?
      if (ch >= 0xD800 && ch <= 0xDFFF) {
        throw new RuntimeException("Invalid character constant: \\u" + new Formatter().format("%04d", ch));
      }
      return Character.valueOf((char) ch);
    } else if (ch == 'o') {
      return Character.valueOf(readOctalChar());
    } else if (compareNext(ch, "newline")) {
      return '\n';
    } else if (compareNext(ch, "return")) {
      return '\r';
    } else if (compareNext(ch, "space")) {
      return ' ';
    } else if (compareNext(ch, "tab")) {
      return '\t';
    } else if (compareNext(ch, "backspace")) {
      return '\b';
    } else if (compareNext(ch, "formfeed")) {
      return '\f';
    }

    throw new RuntimeException("Error parsing character" + context());
  }


  ////////////////
  // readSymbol //
  ////////////////
  
  public Object readSymbol() {
    char[] buf   = reader.buffer();
    int    start = reader.position();
    int    pos   = start;
    int    len   = buf.length;
    int    slash = -1;

    for (; pos < len; ++pos) {
      char ch = buf[pos];
      if (CharReader.isBoundary(ch)) {
        reader.position(pos);
        return finalizeSymbol(buf, start, slash, pos);
      } else if (ch == '/' && slash == -1) {
        slash = pos;
      }
    }

    reader.position(pos);
    return readSymbolComplex(buf, start, slash, pos);
  }

  public Object readSymbolComplex(char[] buf, int start, int slash, int pos) {
    accumulatorLength = 0;
    accumulatorAppend(buf, start, pos);
    if (slash != -1) {
      slash = slash - start;
    }

    outer:
    while (true) {
      buf = reader.buffer();
      if (buf == null) {
        break;
      }
      start = reader.position();
      pos = start;
      int len = reader.bufferLength();

      for (; pos < len; ++pos) {
        char ch = buf[pos];
        if (CharReader.isBoundary(ch)) {
          accumulatorAppend(buf, start, pos);
          reader.position(pos);
          break outer;
        } else if (ch == '/' && slash == -1) {
          slash = accumulatorLength + pos - start;
        }
      }

      accumulatorAppend(buf, start, len);
      reader.nextBuffer();
    }

    return finalizeSymbol(accumulator, 0, slash, accumulatorLength);
  }

  public Object continueReadingSymbol(char ch) {
    if (reader.position() > 0) {
      reader.position(reader.position() - 1);
      return readSymbol();
    } else {
      accumulator[0] = ch;
      return readSymbolComplex(accumulator, 0, -1, 1);
    }
  }

  public Object finalizeSymbol(char[] buf, int start, int slash, int end) {
    if (end == start) {
      throw new RuntimeException("Symbol can't be empty" + context());
    }

    if (1 == end - start && buf[start] == '/') {
      return Symbol.intern(null, "/");
    }

    if (3 == end - start && buf[start] == 'n' && buf[start + 1] == 'i' && buf[start + 2] == 'l') {
      return null;
    }

    if (4 == end - start && buf[start] == 't' && buf[start + 1] == 'r' && buf[start + 2] == 'u' && buf[start + 3] == 'e') {
      return Boolean.TRUE;
    }

    if (5 == end - start && buf[start] == 'f' && buf[start + 1] == 'a' && buf[start + 2] == 'l' && buf[start + 3] == 's' && buf[start + 4] == 'e') {
      return Boolean.FALSE;
    }

    if (slash == -1) {
      return Symbol.intern(null, new String(buf, start, end - start));
    }

    if (slash == start) {
      throw new RuntimeException("Symbol's namespace can't be empty: " + new String(buf, start, end - start) + context());
    }

    if (slash == end - 1) {
      throw new RuntimeException("Symbol's name can't be empty: " + new String(buf, start, end - start) + context());
    }

    return Symbol.intern(new String(buf, start, slash - start),
                         new String(buf, slash + 1, end - (slash + 1)));
  }

  /////////////////
  // readKeyword //
  /////////////////

  public Keyword readKeyword() {
    char[] buf   = reader.buffer();
    int    start = reader.position();
    int    pos   = start;
    int    len   = buf.length;
    int    slash = -1;
    for (; pos < len; ++pos) {
      char ch = buf[pos];
      if (CharReader.isBoundary(ch)) {
        reader.position(pos);
        return finalizeKeyword(buf, start, slash, pos);
      } else if (ch == '/' && slash == -1) {
        slash = pos;
      }
    }
    reader.position(pos);
    return readKeywordComplex(buf, start, slash, pos);
  }

  public Keyword readKeywordComplex(char[] buf, int start, int slash, int pos) {
    accumulatorLength = 0;
    accumulatorAppend(buf, start, pos);
    if (slash != -1) {
      slash = slash - start;
    }

    outer:
    while (true) {
      buf = reader.buffer();
      if (buf == null) {
        break;
      }
      start = reader.position();
      pos = start;
      int len = reader.bufferLength();

      for (; pos < len; ++pos) {
        char ch = buf[pos];
        if (CharReader.isBoundary(ch)) {
          accumulatorAppend(buf, start, pos);
          reader.position(pos);
          break outer;
        } else if (ch == '/' && slash == -1) {
          slash = accumulatorLength + pos - start;
        }
      }

      accumulatorAppend(buf, start, len);
      reader.nextBuffer();
    }

    return finalizeKeyword(accumulator, 0, slash, accumulatorLength);
  }

  public Keyword finalizeKeyword(char[] buf, int start, int slash, int end) {
    if (end == start) {
      throw new RuntimeException("Keyword can't be empty" + context());
    }

    if (1 == end - start && buf[start] == '/') {
      return Keyword.intern(Symbol.intern(null, "/"));
    }

    if (slash == -1) {
      return Keyword.intern(Symbol.intern(null, new String(buf, start, end - start)));
    }

    if (slash == start) {
      throw new RuntimeException("Keyword's namespace can't be empty: " + new String(buf, start, end - start) + context());
    }

    if (slash == end - 1) {
      throw new RuntimeException("Keyword's name can't be empty: " + new String(buf, start, end - start) + context());
    }

    return Keyword.intern(Symbol.intern(new String(buf, start, slash - start),
                                        new String(buf, slash + 1, end - (slash + 1))));
  }


  ////////////////
  // readNumber //
  ////////////////

  public Number readNumber() {
    char[] buf   = reader.buffer();
    int    start = reader.position();
    int    pos   = start;
    int    len   = buf.length;
    long   val   = 0;

    for (; pos < len; ++pos) {
      char ch = buf[pos];
      if (ch >= '0' && ch <= '9') {
        val = val * 10 + ch - '0';
      } else if (CharReader.isBoundary(ch)) {
        reader.position(pos);
        return val;
      } else {
        break;
      }
    }

    reader.position(pos);
    return readNumberComplex(buf, start, pos);
  }

  public Number readNumberComplex(char[] buf, int start, int pos) {
    accumulatorLength = 0;
    accumulatorAppend(buf, start, pos);

    boolean isInt    = false;
    boolean isFloat  = false;
    boolean isRatio  = false;
    int     radixPos = -1;

    outer:
    while (true) {
      buf = reader.buffer();
      if (buf == null) {
        break;
      }
      start = reader.position();
      pos = start;
      int len = reader.bufferLength();

      for (; pos < len; ++pos) {
        char ch = buf[pos];
        if (ch >= '0' && ch <= '9') {
          // pass
        } else if (CharReader.isBoundary(ch)) {
          accumulatorAppend(buf, start, pos);
          reader.position(pos);
          break outer;
        } else if (!isInt && !isFloat && (ch == '.' || ch == 'e' || ch == 'E' || ch == 'M')) {
          isFloat = true;
        } else if (!isInt && !isFloat && (ch == 'x' || ch == 'X' || ch == 'N')) {
          isInt = true;
        } else if (radixPos == -1 && (ch == 'r' || ch == 'R')) {
          radixPos = accumulatorLength + pos - start;
          isInt = true;
        } else if (ch == '/') {
          accumulatorAppend(buf, start, pos);
          reader.position(pos + 1);
          isRatio = true;
          break outer;
        }
      }

      accumulatorAppend(buf, start, len);
      reader.nextBuffer();
    }

    if (isRatio) {
      Number numerator = finalizeInt(accumulator, 0, radixPos, accumulatorLength);
      return finalizeRatio(numerator);
    }

    if (isFloat) {
      return finalizeFloat(accumulator, 0, accumulatorLength);
    }

    return finalizeInt(accumulator, 0, radixPos, accumulatorLength);
  }

  public Number finalizeRatio(Number numerator) {
    numerator = numerator instanceof BigInt ? Numbers.reduceBigInt((BigInt) numerator) : numerator;
    if (!(numerator instanceof Long || numerator instanceof BigInteger)) {
      throw new RuntimeException("Numerator can't be " + numerator.getClass().getName() + ": " + numerator + context());
    }
    numerator = numerator instanceof Long ? BigInteger.valueOf((Long) numerator) : (BigInteger) numerator;

    Number denominator = readNumber();
    denominator = denominator instanceof BigInt ? Numbers.reduceBigInt((BigInt) denominator) : denominator;
    if (!(denominator instanceof Long || denominator instanceof BigInteger)) {
      throw new RuntimeException("Denominator can't be " + denominator.getClass().getName() + ": " + denominator + context());
    }
    denominator = denominator instanceof Long ? BigInteger.valueOf((Long) denominator) : (BigInteger) denominator;

    return Numbers.divide(numerator, denominator);
  }

  public Number finalizeFloat(char[] buf, int start, int end) {
    final int len = end - start;
    if (buf[end - 1] == 'M') {
      return new BigDecimal(buf, start, len - 1);
    } else {
      return Double.parseDouble(new String(buf, start, len));
    }
  }

  public Number finalizeInt(char[] buf, int start, int radixPos, int end) {
    int radix = 10;
    boolean forceBigInt = false;
    
    if (radixPos != -1) {
      radix = (int) Long.parseLong(new CharSeq(buf), start, radixPos, 10);
      start = radixPos + 1;
    }

    if (buf[end - 1] == 'N') {
      forceBigInt = true;
      end = end - 1;
    }

    if (buf[start] == '0') {
      if (end - start >= 3 && buf[start + 1] == 'x' || buf[start + 1] == 'X') {
        radix = 16;
        start = start + 2;
      } else if (end - start >= 2) {
        radix = 8;
        start = start + 1;
      }
    }

    if (forceBigInt) {
      String str = new String(buf, start, end - start);
      BigInteger bn = new BigInteger(str, radix);
      return BigInt.fromBigInteger(bn);
    }

    try {
      return Long.valueOf(Long.parseLong(new CharSeq(buf), start, end, radix));
    } catch (Exception e) {
      String str = new String(buf, start, end - start);
      BigInteger bn = new BigInteger(str, radix);
      return BigInt.fromBigInteger(bn);
    }
  }


  ////////////////////

  public final String context() {
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
        if (++count != acc.count()) {
          throw new IllegalArgumentException("Duplicate key: " + key + " reading set: " + String.valueOf(acc.persistent()) + context());
        }
      }
    }
    throw Util.runtimeException("EOF while reading set: " + String.valueOf(acc.persistent()) + context());
  }

  public final Object readMap(String ns) throws Exception {
    ATransientMap acc = (ATransientMap) PersistentArrayMap.EMPTY.asTransient();
    int count = 0;
    while (!reader.eof()) {
      char nextChar = reader.eatwhite();
      if (nextChar == '}') {
        return acc.persistent();
      } else if (nextChar == 0) {
        break;
      } else {
        reader.unread();
        Object key = readObject();
        if (ns != null) {
          if (key instanceof Keyword) {
            Keyword kw = (Keyword) key;
            if (kw.getNamespace() == null) {
              key = Keyword.intern(ns, kw.getName());
            } else if (kw.getNamespace().equals("_")) {
              key = Keyword.intern(null, kw.getName());
            }
          } else if(key instanceof Symbol) {
            Symbol s = (Symbol) key;
            if (s.getNamespace() == null) {
              key = Symbol.intern(ns, s.getName());
            } else if (s.getNamespace().equals("_")) {
              key = Symbol.intern(null, s.getName());
            }
          }
        }
        nextChar = reader.eatwhite();
        if (nextChar == '}') {
          throw Util.runtimeException("Map literal must contain an even number of forms: " + String.valueOf(acc.persistent()) + context());
        }
        reader.unread();
        Object val = readObject();
        acc = (ATransientMap) acc.assoc(key, val);
        if (++count != acc.count()) {
          throw new IllegalArgumentException("Duplicate key: " + key + " reading map: " + String.valueOf(acc.persistent()) + context());
        }
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
        return readMap(null);
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
          } else if (res instanceof Ratio) {
            BigInteger numerator = ((Ratio) res).numerator;
            BigInteger denominator = ((Ratio) res).denominator;
            return new Ratio(numerator.negate(), denominator);
          } else {
            throw Util.runtimeException("Unexpected number type " + res.getClass().getName() + ": " + res + context());
          }
        } else {
          reader.unread();
          return continueReadingSymbol('-');
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
          return continueReadingSymbol('+');
        }
      }
      case '#': {
        int nextChar = reader.read();
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
          nextChar = reader.read();
          if (compareNext(nextChar, "Inf")) {
            return Double.POSITIVE_INFINITY;
          } else if (compareNext(nextChar, "-Inf")) {
            return Double.NEGATIVE_INFINITY;
          } else if (compareNext(nextChar, "NaN")) {
            return Double.NaN;
          } else {
            throw new RuntimeException("Unknown symbolic value" + context());
          }
        }

        if (nextChar == ':') {
          Keyword ns = (Keyword) readKeyword();
          if (ns.getNamespace() != null) {
            throw new RuntimeException("Namespaced map should use non-namespaced keyword: :" + ns + context());
          }
          val = reader.eatwhite();
          if (val != '{') {
            throw new RuntimeException("Namespaced map must specify a map: " + context());
          }
          return readMap(ns.getName());
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
