package fast_edn;

import clojure.lang.*;
import java.io.*;
import java.math.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

@SuppressWarnings("unchecked")
public class EdnParser {
  public final static Keyword TAG_KEY = Keyword.intern(null, "tag");
  public final static Keyword PARAM_TAGS_KEY = Keyword.intern(null, "param-tags");

  public final ILookup dataReaders;
  public final IFn     defaultDataReader;
  public final boolean throwOnEOF;
  public final Object  eofValue;

  public Reader   reader = null;
  public char[]   readBuf;
  public int      readPos = 0;
  public int      readGlobalPos = 0;
  public int      readLen = 0;
  public char[]   accumulator;
  public int      accumulatorLength;
  public Object[] arrayMapBuf = new Object[16];

  public EdnParser(int bufferSize, ILookup dataReaders, IFn defaultDataReader, boolean throwOnEOF, Object eofValue) {
    this.dataReaders = dataReaders;
    this.defaultDataReader = defaultDataReader;
    this.throwOnEOF = throwOnEOF;
    this.eofValue = eofValue;

    this.readBuf = new char[bufferSize];
    this.accumulator = new char[32];
    this.accumulatorLength = 0;
  }

  public EdnParser withReader(Reader reader) {
    this.reader = reader;
    this.readPos = 0;
    this.readGlobalPos = 0;
    this.readLen = 0;
    return this;
  }


  ////////////
  // Reader //
  ////////////

  public void nextBuffer() {
    if (readLen != -1) {
      try {
        readGlobalPos += readLen;
        readLen = reader.read(readBuf, 0, readBuf.length);
        if (readLen >= 0) {
          readPos = 0;
        }
      } catch (IOException e) {
        Util.sneakyThrow(e);
      }
    }
  }

  public int read() {
    if (readLen > readPos) {
      return readBuf[readPos++];
    }
    nextBuffer();
    return readLen == -1 ? -1 : readBuf[readPos++];
  }

  public void unread() {
    assert readPos > 0;
    readPos -= 1;
  }

  public boolean eof() {
    return readLen == -1;
  }

  public int skip(IntPredicate pred) {
    while (!eof()) {
      char[] buf = readBuf;
      int    pos = readPos;
      int    len = readLen;
      for (; pos < len; ++pos) {
        int ch = buf[pos];
        if (!pred.test(ch)) {
          readPos = pos + 1;
          return ch;
        }
      }
      nextBuffer();
    }
    return -1;
  }

  public int skipWhitespace() {
    return skip(EdnParser::isWhitespace);
  }

  public boolean compareNext(int ch, String s) {
    if ((char) ch != s.charAt(0)) {
      return false;
    }

    for (int i = 1; i < s.length(); ++i) {
      ch = read();
      if (ch == -1) {
        throw new RuntimeException("EOF while reading" + context());
      }
      if ((char) ch != s.charAt(i)) {
        return false;
      }
    }
    return true;
  }
  
  public String context() {
    int len = readLen == -1 ? readPos : readLen; 
    if (len <= 0) {
      return "";
    }

    int start = readPos;
    for (; start > Math.max(0, readPos - 100); --start) {
      int ch = readBuf[start];
      if (ch == '\n' || ch == '\r') {
        start = Math.min(start + 1, readPos);
        break;
      }
    }

    int end = readPos;
    for (; end < Math.min(len, readPos + 100); ++end) {
      int ch = readBuf[end];
      if (ch == '\n' || ch == '\r') {
        end = Math.max(end - 1, readPos);
        break;
      }
    }

    if (end == start) {
      return "";
    }

    int offset = readGlobalPos + (readLen == -1 ? 0 : readPos);

    final char[] indentArray = new char[readPos - start - 1];
    Arrays.fill(indentArray, ' ');
    String indent = new String(indentArray);

    return ", offset: " + offset + ", context:\n" + new String(readBuf, start, end - start) + "\n" + indent + "^";
  }


  /////////////////
  // Accumulator //
  /////////////////

  public void accumulatorEnsureCapacity(int len) {
    if (len > accumulator.length) {
      accumulator = Arrays.copyOf(accumulator, Integer.highestOneBit(len) << 1);
    }
  }

  public void accumulatorAppend(char ch) {
    // TODO if accumulatorLength + 1 <= accumulator.length
    accumulatorEnsureCapacity(accumulatorLength + 1);
    accumulator[accumulatorLength] = ch;
    accumulatorLength += 1;
  }

  public void accumulatorAppend(char[] data, int start, int end) {
    int len = end - start;
    if (len > 0) {
      accumulatorEnsureCapacity(accumulatorLength + len);
      // TODO if (len < 5)
      System.arraycopy(data, start, accumulator, accumulatorLength, len);
      accumulatorLength += len;
    }
  }

  public String accumulatorToString() {
    return new String(accumulator, 0, accumulatorLength);
  }


  ////////////////
  // readString //
  ////////////////

  public String readString() {
    char[] buf   = readBuf;
    int    start = readPos;
    int    pos   = start;
    int    len   = readLen;
    for (; pos < len; ++pos) {
      char ch = buf[pos];
      if (ch == '"') {
        readPos = pos + 1;
        return new String(buf, start, pos - start);
      }
      if (ch == '\\') {
        break;
      }
    }
    readPos = pos;
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

    throw new RuntimeException("Unexpected digit: " + ((char) ch) + context());
  }

  public char readUnicodeChar() {
    char[] buf = readBuf;
    int pos = readPos;
    if (pos + 4 < readLen) {
      int ch = (digit16(buf[pos    ]) << 12)
             + (digit16(buf[pos + 1]) << 8)
             + (digit16(buf[pos + 2]) << 4)
             +  digit16(buf[pos + 3]);
      readPos = pos + 4;
      return (char) ch;
    }

    int ch = (digit16(read()) << 12)
           + (digit16(read()) << 8)
           + (digit16(read()) << 4)
           +  digit16(read());
    return (char) ch;
  }

  public char readOctalChar() {
    int value = 0;
    for (int i = 0; i < 3; ++i) {
      int ch = read();
      if (ch >= '0' && ch <= '7') {
        value = (value << 3) + (ch - '0');
      } else {
        unread();
        break;
      }
    }

    if (value > 0377) {
      throw new RuntimeException("Octal escape sequence must be in range [0, 377], got: " + Integer.toString(value, 8) + context());
    }
    
    return (char) value;
  }

  public String readStringComplex(char[] buf, int start, int pos) {
    accumulatorLength = 0;
    accumulatorAppend(buf, start, pos);

    outer:
    while (!eof()) {
      buf     = readBuf;
      start   = readPos;
      pos     = start;
      int len = readLen;

      for (; pos < len; ++pos) {
        char ch1 = buf[pos];

        if (ch1 == '\\') {
          accumulatorAppend(buf, start, pos);
          readPos = pos + 1;
          int ch2 = read();

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
            unread();
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
        } else if (ch1 == '"') {
          readPos = pos + 1;
          accumulatorAppend(buf, start, pos);
          return accumulatorToString();
        }
      }

      accumulatorAppend(buf, start, len);
      nextBuffer();
    }

    throw new RuntimeException("EOF while reading string: \"" + accumulatorToString() + context());
  }


  ///////////////////
  // readCharacter //
  ///////////////////

  public Character readCharacter() {
    int ch = read();
    
    if (-1 == ch) {
      throw new RuntimeException("EOF while reading character" + context());
    }

    int peek = read();
    unread();

    if (peek == -1 || isBoundary(peek)) {
      return (char) ch;
    }

    if (ch == 'u') {
      ch = readUnicodeChar();
      // surrogate code unit?
      if (ch >= 0xD800 && ch <= 0xDFFF) {
        throw new RuntimeException("Invalid character constant: \\u" + new Formatter().format("%04d", ch) + context());
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
    char[] buf   = readBuf;
    int    start = readPos;
    int    pos   = start;
    int    len   = readLen;
    int    slash = -1;

    for (; pos < len; ++pos) {
      char ch = buf[pos];
      if (isBoundary(ch)) {
        readPos = pos;
        return finalizeSymbol(buf, start, slash, pos);
      } else if (ch == '/' && slash == -1) {
        slash = pos;
      }
    }

    readPos = pos;
    return readSymbolComplex(buf, start, slash, pos);
  }

  public Object readSymbolComplex(char[] buf, int start, int slash, int pos) {
    accumulatorLength = 0;
    accumulatorAppend(buf, start, pos);
    if (slash != -1) {
      slash = slash - start;
    }

    outer:
    while (!eof()) {
      buf     = readBuf;
      start   = readPos;
      pos     = start;
      int len = readLen;

      for (; pos < len; ++pos) {
        char ch = buf[pos];
        if (isBoundary(ch)) {
          accumulatorAppend(buf, start, pos);
          readPos = pos;
          break outer;
        } else if (ch == '/' && slash == -1) {
          slash = accumulatorLength + pos - start;
        }
      }

      accumulatorAppend(buf, start, len);
      nextBuffer();
    }

    return finalizeSymbol(accumulator, 0, slash, accumulatorLength);
  }

  public Object continueReadingSymbol(char ch) {
    if (readPos > 0) {
      readPos = readPos - 1;
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


  ////////////////
  // readTagged //
  ////////////////

  public Object readTagged() {
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

    throw new RuntimeException("No dispatch macro for: #" + tag + context());
  }


  /////////////////
  // readKeyword //
  /////////////////

  public Keyword readKeyword() {
    char[] buf   = readBuf;
    int    start = readPos;
    int    pos   = start;
    int    len   = readLen;
    int    slash = -1;
    for (; pos < len; ++pos) {
      char ch = buf[pos];
      if (isBoundary(ch)) {
        readPos = pos;
        return finalizeKeyword(buf, start, slash, pos);
      } else if (ch == '/' && slash == -1) {
        slash = pos;
      }
    }
    readPos = pos;
    return readKeywordComplex(buf, start, slash, pos);
  }

  public Keyword readKeywordComplex(char[] buf, int start, int slash, int pos) {
    accumulatorLength = 0;
    accumulatorAppend(buf, start, pos);
    if (slash != -1) {
      slash = slash - start;
    }

    outer:
    while (!eof()) {
      buf     = readBuf;
      start   = readPos;
      pos     = start;
      int len = readLen;

      for (; pos < len; ++pos) {
        char ch = buf[pos];
        if (isBoundary(ch)) {
          accumulatorAppend(buf, start, pos);
          readPos = pos;
          break outer;
        } else if (ch == '/' && slash == -1) {
          slash = accumulatorLength + pos - start;
        }
      }

      accumulatorAppend(buf, start, len);
      nextBuffer();
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
    char[] buf   = readBuf;
    int    start = readPos;
    int    pos   = start;
    int    len   = readLen;
    long   val   = 0;

    for (; pos < len; ++pos) {
      char ch = buf[pos];
      if (ch >= '0' && ch <= '9') {
        val = val * 10 + ch - '0';
      } else if (isBoundary(ch)) {
        readPos = pos;
        return val;
      } else {
        break;
      }
    }

    readPos = pos;
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
    while (!eof()) {
      buf     = readBuf;
      start   = readPos;
      pos     = start;
      int len = readLen;

      for (; pos < len; ++pos) {
        char ch = buf[pos];
        if (ch >= '0' && ch <= '9') {
          // pass
        } else if (isBoundary(ch)) {
          accumulatorAppend(buf, start, pos);
          readPos = pos;
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
          readPos = pos + 1;
          isRatio = true;
          break outer;
        }
      }

      accumulatorAppend(buf, start, len);
      nextBuffer();
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
    int len = end - start;
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
      radix = (int) Long.parseLong(new String(buf, start, radixPos - start), 10);
      start = radixPos + 1;
    }

    if (buf[end - 1] == 'N') {
      forceBigInt = true;
      end = end - 1;
    }

    if (buf[start] == '0') {
      if (end - start >= 3 && (buf[start + 1] == 'x' || buf[start + 1] == 'X')) {
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

    String str = new String(buf, start, end - start);
    try {
      return Long.valueOf(Long.parseLong(str, radix));
    } catch (Exception e) {
      BigInteger bn = new BigInteger(str, radix);
      return BigInt.fromBigInteger(bn);
    }
  }

  public Number readNumberNegative() {
    Number n = readNumber();
    if (n instanceof Long) {
      return Long.valueOf(-((Long) n).longValue());
    } else if (n instanceof Double) {
      return Double.valueOf(-((Double) n).doubleValue());
    } else if (n instanceof BigInt) {
      return BigInt.fromBigInteger(((BigInt) n).toBigInteger().negate());
    } else if (n instanceof BigDecimal) {
      return ((BigDecimal) n).negate();
    } else if (n instanceof Ratio) {
      BigInteger numerator = ((Ratio) n).numerator;
      BigInteger denominator = ((Ratio) n).denominator;
      return new Ratio(numerator.negate(), denominator);
    } else {
      throw new RuntimeException("Unexpected number class " + toClassString(n) + context());
    }
  }

  public Double readSymbolicValue() {
    int ch = read();
    if (compareNext(ch, "Inf")) {
      return Double.POSITIVE_INFINITY;
    } else if (compareNext(ch, "-Inf")) {
      return Double.NEGATIVE_INFINITY;
    } else if (compareNext(ch, "NaN")) {
      return Double.NaN;
    } else {
      throw new RuntimeException("Unknown symbolic value" + context());
    }
  }


  //////////////
  // readList //
  //////////////

  public IPersistentList readList() {
    ArrayList acc = new ArrayList();

    while (!eof()) {
      int ch = skipWhitespace();

      if (ch == ')') {
        IPersistentList res = PersistentList.EMPTY;
        for (ListIterator i = acc.listIterator(acc.size()); i.hasPrevious(); ) {
          res = (IPersistentList) res.cons(i.previous());
        }
        return res;
      } else if (ch == -1) {
        break;
      } else {
        unread();
        acc.add(readObject());
      }
    }

    throw new RuntimeException("EOF while reading list: " + toUnfinishedCollString(acc) + context());
  }


  ////////////////
  // readVector //
  ////////////////

  public PersistentVector readVector() {
    ITransientCollection acc = PersistentVector.EMPTY.asTransient();

    while (!eof()) {
      int ch = skipWhitespace();

      if (ch == ']') {
        return (PersistentVector) acc.persistent();
      } else if (ch == -1) {
        break;
      } else {
        unread();
        acc = acc.conj(readObject());
      }
    }

    throw new RuntimeException("EOF while reading vector: " + toUnfinishedCollString(acc.persistent()) + context());
  }


  /////////////
  // readSet //
  /////////////

  public PersistentHashSet readSet() {
    ATransientSet acc = (ATransientSet) PersistentHashSet.EMPTY.asTransient();
    int count = 0;

    while (!eof()) {
      int ch = skipWhitespace();

      if (ch == '}') {
        return (PersistentHashSet) acc.persistent();
      } else if (ch == -1) {
        break;
      } else {
        unread();
        Object key = readObject();
        acc = (ATransientSet) acc.conj(key);
        if (count + 1 != acc.count()) {
          throw new RuntimeException("Duplicate key: " + key + " reading set: " + toUnfinishedCollString(acc.persistent()) + context());
        }
        count = count + 1;
      }
    }

    throw new RuntimeException("EOF while reading set: " + toUnfinishedCollString(acc.persistent()) + context());
  }


  /////////////
  // readMap //
  /////////////

  public IPersistentMap readMap(String ns) {
    int len = 0;
    while (!eof()) {
      int ch = skipWhitespace();

      if (ch == '}') {
        return PersistentArrayMap.createWithCheck(Arrays.copyOf(arrayMapBuf, len));
      } else if (ch == -1) {
        break;
      } else if (len >= arrayMapBuf.length) {
        return readHashMap(ns, Arrays.copyOf(arrayMapBuf, len));
      } else {
        unread();
        Object key = readObject();
        if (ns != null) {
          if (key instanceof Keyword) {
            Keyword kw = (Keyword) key;
            if (kw.getNamespace() == null) {
              key = Keyword.intern(ns, kw.getName());
            } else if (kw.getNamespace().equals("_")) {
              key = Keyword.intern(null, kw.getName());
            }
          } else if (key instanceof Symbol) {
            Symbol s = (Symbol) key;
            if (s.getNamespace() == null) {
              key = Symbol.intern(ns, s.getName());
            } else if (s.getNamespace().equals("_")) {
              key = Symbol.intern(null, s.getName());
            }
          }
        }

        ch = skipWhitespace();
        if (ch == '}' || ch == -1) {
          throw new RuntimeException("Map literal must contain an even number of forms: " + toUnfinishedCollString(PersistentArrayMap.createWithCheck(Arrays.copyOf(arrayMapBuf, len))) + ", " + key + context());
        }

        unread();
        Object val = readObject();
        
        arrayMapBuf[len++] = key;
        arrayMapBuf[len++] = val;
      }
    }
    
    throw new RuntimeException("EOF while reading map: " + toUnfinishedCollString(PersistentArrayMap.createWithCheck(Arrays.copyOf(arrayMapBuf, len))) + context());
  }

  public IPersistentMap readHashMap(String ns, Object[] init) {
    ATransientMap acc = (ATransientMap) PersistentArrayMap.EMPTY.asTransient();
    
    int count = 0;
    for (int i = 0; i < init.length; i += 2) {
      acc = (ATransientMap) acc.assoc(init[i], init[i + 1]);
      count = count + 1;
      if (acc.count() != count) {
        throw new RuntimeException("Duplicate key: " + init[i] + " reading map: " + toUnfinishedCollString(acc.persistent()) + context());
      }
    }

    while (!eof()) {
      int ch = skipWhitespace();

      if (ch == '}') {
        return acc.persistent();
      } else if (ch == -1) {
        break;
      } else {
        unread();
        Object key = readObject();
        if (ns != null) {
          if (key instanceof Keyword) {
            Keyword kw = (Keyword) key;
            if (kw.getNamespace() == null) {
              key = Keyword.intern(ns, kw.getName());
            } else if (kw.getNamespace().equals("_")) {
              key = Keyword.intern(null, kw.getName());
            }
          } else if (key instanceof Symbol) {
            Symbol s = (Symbol) key;
            if (s.getNamespace() == null) {
              key = Symbol.intern(ns, s.getName());
            } else if (s.getNamespace().equals("_")) {
              key = Symbol.intern(null, s.getName());
            }
          }
        }

        ch = skipWhitespace();
        if (ch == '}' || ch == -1) {
          throw new RuntimeException("Map literal must contain an even number of forms: " + toUnfinishedCollString(acc.persistent()) + ", " + key + context());
        }

        unread();
        Object val = readObject();
        acc = (ATransientMap) acc.assoc(key, val);
        count = count + 1;
        if (acc.count() != count) {
          throw new RuntimeException("Duplicate key: " + key + " reading map: " + toUnfinishedCollString(acc.persistent()) + context());
        }
      }
    }

    throw new RuntimeException("EOF while reading map: " + toUnfinishedCollString(acc.persistent()) + context());
  }


  //////////////
  // readMeta //
  //////////////

  public Object readMeta() {
    Object meta = readObject(true);

    if (meta instanceof Symbol || meta instanceof String) {
      meta = RT.map(TAG_KEY, meta);
    } else if (meta instanceof IPersistentVector) {
      meta = RT.map(PARAM_TAGS_KEY, meta);
    } else if (meta instanceof Keyword) {
      meta = RT.map(meta, RT.T);
    } else if (!(meta instanceof IPersistentMap)) {
      throw new RuntimeException("Metadata must be Symbol, Keyword, String, Map or Vector, got " + toClassString(meta) + context());
    }

    Object obj = readObject(true);
    if (!(obj instanceof IMeta)) {
      throw new RuntimeException("Can't put meta on " + toClassString(obj) + context());
    }

    IPersistentMap objMeta = (IPersistentMap) RT.meta(obj);
    meta = ((IKVReduce) meta).kvreduce(new AFn() {
      @Override
      public Object invoke(Object objMeta, Object key, Object obj) {
        return RT.assoc(objMeta, key, obj);
      }
    }, objMeta);

    return ((IObj) obj).withMeta((IPersistentMap) meta);
  }


  ////////////////
  // readObject //
  ////////////////

  public Object readObject() {
    return readObject(throwOnEOF);
  }

  public Object readObject(boolean throwOnEOF) {
    if (reader == null) {
      return null;
    }

    while (true) {
      int ch1 = skipWhitespace();

      switch (ch1) {
        case '"': {
          return readString();
        }

        case ':': {
          return readKeyword();
        }

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
          skip(ch -> '\n' != ch && '\r' != ch);
          continue;
        }

        case '-': {
          int ch2 = read();

          if (-1 == ch2 || isBoundary(ch2)) {
            return Symbol.intern(null, "-");
          } else if ('0' <= ch2 && ch2 <= '9') {
            unread();
            return readNumberNegative();
          } else {
            unread();
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
          int ch2 = read();

          if (-1 == ch2 || isBoundary(ch2)) {
            return Symbol.intern(null, "+");
          } else if ('0' <= ch2 && ch2 <= '9') {
            unread();
            return readNumber();
          } else {
            unread();
            return continueReadingSymbol('+');
          }
        }

        case '#': {
          int ch2 = read();

          if (ch2 == -1) {
            throw new RuntimeException("EOF while reading dispatch macro" + context());
          }

          if (ch2 == '{') {
            return readSet();
          }

          if (ch2 == '_') {
            readObject(true);
            continue;
          }

          if (ch2 == '#') {
            return readSymbolicValue();
          }

          if (ch2 == ':') {
            Keyword ns = (Keyword) readKeyword();

            if (ns.getNamespace() != null) {
              throw new RuntimeException("Namespaced map should use non-namespaced keyword: " + ns + context());
            }

            int ch3 = skipWhitespace();
            if (ch3 != '{') {
              throw new RuntimeException("Namespaced map must specify a map: " + ns + context());
            }

            return readMap(ns.getName());
          }

          unread();
          return readTagged();
        }

        case -1: {
          if (throwOnEOF) {
            throw new RuntimeException("EOF while reading" + context());
          } else {
            return eofValue;
          }
        }

        default: {
          if ('0' <= ch1 && ch1 <= '9') {
            unread();
            return readNumber();
          } else if (!isBoundary(ch1)) {
            unread();
            return readSymbol();
          }

          throw new RuntimeException("Unexpected character: " + ((char) ch1) + context());
        }
      }
    }
  }


  //////////
  // Misc //
  //////////

  public static final BitSet whitespaceMask = new BitSet(0x30);
  public static final BitSet boundaryMask = new BitSet(0x80);
  
  static {
    whitespaceMask.set('\t');
    whitespaceMask.set('\n');
    whitespaceMask.set('\r');
    whitespaceMask.set(' ');
    whitespaceMask.set(',');

    boundaryMask.set('\t');
    boundaryMask.set('\n');
    boundaryMask.set('\r');
    boundaryMask.set(' ');
    boundaryMask.set(',');
    boundaryMask.set('"');
    boundaryMask.set('(');
    boundaryMask.set(')');
    boundaryMask.set('[');
    boundaryMask.set('\\');
    boundaryMask.set(']');
    boundaryMask.set('{');
    boundaryMask.set('}');
  }

  public static boolean isWhitespace(int ch) {
    return ch < 0x30 && whitespaceMask.get(ch);
  }

  public static boolean isBoundary(int ch) {
    return ch < 0x80 && boundaryMask.get(ch);
  }

  public static String toClassString(Object o) {
    if (o == null) {
      return "null";
    }

    return o.getClass().getName() + ": " + o.toString();
  }

  public static String toUnfinishedCollString(Object o) {
    if (o instanceof APersistentVector) {
      return (String) ((APersistentVector) o).stream().map(String::valueOf).collect(Collectors.joining(" ", "[", ""));
    }

    if (o instanceof APersistentSet) {
      return (String) ((APersistentSet) o).stream().map(String::valueOf).collect(Collectors.joining(" ", "#{", ""));
    }

    if (o instanceof APersistentMap) {
      return (String) ((Map<Object, Object>) o).entrySet().stream().map((Map.Entry e) -> String.valueOf(e.getKey()) + " " + String.valueOf(e.getValue())).collect(Collectors.joining(", ", "{", ""));
    }

    if (o instanceof List) {
      return (String) ((List) o).stream().map(String::valueOf).collect(Collectors.joining(" ", "(", ""));
    }

    throw new RuntimeException("Unknown object type " + toClassString(o));
  }
}
