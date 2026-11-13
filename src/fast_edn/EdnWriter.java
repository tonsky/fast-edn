package fast_edn;

import clojure.lang.*;
import java.io.Writer;
import java.util.Arrays;

/**
 * Writes Clojure data as EDN. The output is identical to {@code pr-str} with {@code *print-length*} and
 * {@code *print-level*} nil, {@code *print-readably*} true and {@code *print-dup*}, {@code *print-meta*},
 * {@code *print-namespace-maps*} false, whatever the caller has bound.
 *
 * <p>Clojure's own types are written directly into a {@code char[]}. Every other value (records, user types,
 * {@code java.util} collections) goes through {@code clojure.core/print-method}, which gets this object as its
 * {@link Writer} and so writes into the same buffer.
 *
 * <p>Each thread keeps one instance and reuses its buffer across calls. A call made while the thread's
 * instance is busy (a {@code print-method} that calls {@link #writeString} itself) gets a temporary instance.
 */
public final class EdnWriter extends Writer {
  static final Var PRINT_METHOD = RT.var("clojure.core", "print-method");

  /** Bound around print-method calls, so their output does not depend on the caller's bindings */
  static final IPersistentMap PRINT_BINDINGS = RT.map(
    RT.var("clojure.core", "*print-length*"),         null,
    RT.var("clojure.core", "*print-level*"),          null,
    RT.var("clojure.core", "*print-readably*"),       Boolean.TRUE,
    RT.var("clojure.core", "*print-dup*"),            Boolean.FALSE,
    RT.var("clojure.core", "*print-meta*"),           Boolean.FALSE,
    RT.var("clojure.core", "*print-namespace-maps*"), Boolean.FALSE);

  static final Keyword TYPE = Keyword.intern(null, "type");


  ///////////
  // Kinds //
  ///////////

  // How values of a class are written, for classes outside the common ones checked first in writeValue.
  // Decided once per class (kindOf), then looked up in KIND_BY_CLASS.

  /** No fast path: print-method, or an exception with :strict */
  static final int FALLBACK   = 0;
  /** toString is the EDN form: Integer, Short, Byte, BigInteger, Ratio */
  static final int TO_STRING  = 1;
  static final int SYMBOL     = 2;
  static final int CHAR       = 3;
  /** clojure.lang.BigInt, written with an N suffix */
  static final int BIGINT     = 4;
  /** BigDecimal, written with an M suffix */
  static final int BIGDEC     = 5;
  static final int MAP        = 6;
  static final int VECTOR     = 7;
  static final int SET        = 8;
  static final int SEQ        = 9;
  /** Timestamp, Calendar: #inst through print-method, allowed with :strict */
  static final int OTHER_INST = 10;
  static final int FLOAT      = 11;
  /** java.util.Date: #inst, written directly */
  static final int INST       = 12;
  static final int UUID       = 13;

  static final ClassValue<Integer> KIND_BY_CLASS = new ClassValue<Integer>() {
    @Override protected Integer computeValue(Class<?> c) { return kindOf(c); }
  };

  /**
   * Only classes of package clojure.lang get a collection fast path: records and user types implement the
   * same interfaces but may have their own print-method.
   */
  static int kindOf(Class<?> c) {
    if (c == Integer.class || c == Short.class || c == Byte.class
        || c == java.math.BigInteger.class || c == Ratio.class) {
      return TO_STRING;
    }
    if (c == Float.class)                     return FLOAT;
    if (c == Symbol.class)                    return SYMBOL;
    if (c == Character.class)                 return CHAR;
    if (c == BigInt.class)                    return BIGINT;
    if (c == java.math.BigDecimal.class)      return BIGDEC;
    if (c == java.util.Date.class)            return INST;
    if (c == java.util.UUID.class)            return UUID;
    if (c == java.sql.Timestamp.class || java.util.Calendar.class.isAssignableFrom(c)) {
      return OTHER_INST;
    }
    if (c.getPackage() == null || !"clojure.lang".equals(c.getPackage().getName())) {
      return FALLBACK;
    }
    if (IRecord.class.isAssignableFrom(c))                                          return FALLBACK;
    if (IPersistentMap.class.isAssignableFrom(c) && IKVReduce.class.isAssignableFrom(c)) return MAP;
    if (IPersistentVector.class.isAssignableFrom(c))                               return VECTOR;
    if (IPersistentSet.class.isAssignableFrom(c))                                  return SET;
    if (ISeq.class.isAssignableFrom(c))                                            return SEQ;
    return FALLBACK;
  }


  ////////////
  // Tables //
  ////////////

  /** Names of the chars pr-str writes as \newline, \space, ...; null = written as the char itself */
  static final String[] CHAR_NAMES = new String[33];
  static {
    CHAR_NAMES['\n'] = "newline";
    CHAR_NAMES['\t'] = "tab";
    CHAR_NAMES[' ']  = "space";
    CHAR_NAMES['\b'] = "backspace";
    CHAR_NAMES['\f'] = "formfeed";
    CHAR_NAMES['\r'] = "return";
  }

  /** Escapes inside strings, as clojure.core/char-escape-string; null = written as is */
  static final String[] ESCAPES = new String[128];
  static {
    ESCAPES['\n'] = "\\n";
    ESCAPES['\t'] = "\\t";
    ESCAPES['\r'] = "\\r";
    ESCAPES['"']  = "\\\"";
    ESCAPES['\\'] = "\\\\";
    ESCAPES['\f'] = "\\f";
    ESCAPES['\b'] = "\\b";
  }

  /** "00" "01" ... "99": the two digits of i at 2*i and 2*i+1 */
  static final char[] DIGIT_PAIRS = new char[200];
  static {
    for (int i = 0; i < 100; i++) {
      DIGIT_PAIRS[2 * i]     = (char) ('0' + i / 10);
      DIGIT_PAIRS[2 * i + 1] = (char) ('0' + i % 10);
    }
  }


  ///////////
  // State //
  ///////////

  static final int INITIAL_CAPACITY = 1024;
  /** In chars. A buffer that grew larger is replaced after the call instead of being kept by the thread. */
  static final int MAX_KEPT_CAPACITY = 1 << 20;

  static final ThreadLocal<EdnWriter> THREAD_WRITER = new ThreadLocal<EdnWriter>();

  /** Output so far: out[0, outLen) */
  char[] out;
  int outLen;
  /** Set during writeString; a nested call then uses a temporary instance */
  boolean inUse;
  /** PRINT_BINDINGS pushed by the first print-method call of this writeString; popped at its end */
  boolean bindingsPushed;
  boolean strict;
  /** Reused for every map; nested maps save and restore its state */
  final EntryWriter entryWriter = new EntryWriter();

  EdnWriter(int capacity) {
    out = new char[capacity];
  }

  public static String writeString(Object x) {
    return writeString(x, false);
  }

  /**
   * @param strict throw IllegalArgumentException for values without an EDN form (records, objects, regexes,
   *               ...) instead of writing them through print-method; :type metadata is ignored
   */
  public static String writeString(Object x, boolean strict) {
    EdnWriter w = THREAD_WRITER.get();
    if (w == null || w.inUse) {
      w = new EdnWriter(INITIAL_CAPACITY);
      if (THREAD_WRITER.get() == null) {
        THREAD_WRITER.set(w);
      }
    }
    w.inUse = true;
    w.outLen = 0;
    w.strict = strict;
    try {
      w.writeValue(x);
      return new String(w.out, 0, w.outLen);
    } finally {
      if (w.bindingsPushed) {
        Var.popThreadBindings();
        w.bindingsPushed = false;
      }
      if (w.out.length > MAX_KEPT_CAPACITY) {
        w.out = new char[INITIAL_CAPACITY];
      }
      w.inUse = false;
    }
  }


  ////////////
  // Output //
  ////////////

  void ensureCapacity(int extra) {
    int need = outLen + extra;
    if (need > out.length) {
      out = Arrays.copyOf(out, Math.max(need, out.length * 2));
    }
  }

  void put(char c) {
    if (outLen == out.length) {
      ensureCapacity(1);
    }
    out[outLen++] = c;
  }

  void put(String s) {
    int len = s.length();
    ensureCapacity(len);
    s.getChars(0, len, out, outLen);
    outLen += len;
  }

  // java.io.Writer, only for print-method: it writes the values without a fast path into the same buffer

  @Override public void write(int c) { put((char) c); }
  @Override public void write(String s) { put(s); }
  @Override public void write(String s, int off, int len) {
    ensureCapacity(len);
    s.getChars(off, off + len, out, outLen);
    outLen += len;
  }
  @Override public void write(char[] cbuf, int off, int len) {
    ensureCapacity(len);
    System.arraycopy(cbuf, off, out, outLen, len);
    outLen += len;
  }
  @Override public Writer append(char c) { put(c); return this; }
  @Override public Writer append(CharSequence cs) { put(String.valueOf(cs)); return this; }
  @Override public Writer append(CharSequence cs, int start, int end) {
    put(String.valueOf(cs).substring(start, end));
    return this;
  }
  @Override public void flush() {}
  @Override public void close() {}


  ////////////
  // Values //
  ////////////

  /**
   * The classes of typical data first, by exact class (a pointer compare each); everything else by the
   * kind of its class.
   */
  void writeValue(Object x) {
    if (x == null) {
      put("nil");
      return;
    }
    Class<?> c = x.getClass();
    if (c == String.class) {
      writeStringLiteral((String) x);
    } else if (c == Keyword.class) {
      put(x.toString()); // Keyword caches its printed form
    } else if (c == PersistentArrayMap.class || c == PersistentHashMap.class) {
      if (hasTypeMeta(x)) {
        printOrReject(x);
      } else {
        writeMap((IKVReduce) x);
      }
    } else if (c == Long.class) {
      writeLong((Long) x);
    } else if (c == PersistentVector.class) {
      if (hasTypeMeta(x)) {
        printOrReject(x);
      } else {
        writeVector((PersistentVector) x);
      }
    } else if (c == Boolean.class) {
      put(((Boolean) x) ? "true" : "false");
    } else if (c == Double.class) {
      writeDouble((Double) x);
    } else {
      writeByKind(x, KIND_BY_CLASS.get(c));
    }
  }

  void writeByKind(Object x, int kind) {
    if ((kind == SYMBOL || kind == MAP || kind == VECTOR || kind == SET || kind == SEQ) && hasTypeMeta(x)) {
      printOrReject(x);
      return;
    }
    switch (kind) {
      case TO_STRING:  put(x.toString()); break;
      case SYMBOL:     put(x.toString()); break;
      case CHAR:       writeChar((Character) x); break;
      case BIGINT:     put(x.toString()); put('N'); break;
      case BIGDEC:     put(x.toString()); put('M'); break;
      case MAP:        writeMap((IKVReduce) x); break;
      case VECTOR:     writeOtherVector((IPersistentVector) x); break;
      case SET:        writeSet((java.util.Collection) x); break;
      case SEQ:        writeSeq((ISeq) x); break;
      case OTHER_INST: callPrintMethod(x); break;
      case INST:       writeInst((java.util.Date) x); break;
      case UUID:       put("#uuid \""); put(x.toString()); put('"'); break;
      case FLOAT:      writeDouble(((Float) x).doubleValue(), x); break;
      default:         printOrReject(x);
    }
  }

  /**
   * print-method dispatches on a keyword in (:type (meta x)), so such a value may print differently from its
   * class. With :strict it is written as plain EDN.
   */
  boolean hasTypeMeta(Object x) {
    if (strict || !(x instanceof IMeta)) {
      return false;
    }
    IPersistentMap m = ((IMeta) x).meta();
    return m != null && m.valAt(TYPE) instanceof Keyword;
  }

  void printOrReject(Object x) {
    if (strict) {
      throw new IllegalArgumentException("No EDN form for " + x.getClass().getName());
    }
    callPrintMethod(x);
  }

  void callPrintMethod(Object x) {
    if (!bindingsPushed) {
      Var.pushThreadBindings(PRINT_BINDINGS);
      bindingsPushed = true;
    }
    PRINT_METHOD.invoke(x, this);
  }


  /////////////
  // Strings //
  /////////////

  /**
   * Copies all chars into the buffer at once, then scans the copy. Most strings need no escaping and are done
   * after the scan; otherwise the rest is written again from the first char that needs escaping.
   */
  void writeStringLiteral(String s) {
    int len = s.length();
    ensureCapacity(len + 2);
    out[outLen++] = '"';
    int start = outLen;
    s.getChars(0, len, out, start);
    int end = start + len;
    for (int i = start; i < end; ++i) {
      char ch = out[i];
      if (ch < 128 && ESCAPES[ch] != null) {
        outLen = i;
        writeEscapedFrom(s, i - start);
        put('"');
        return;
      }
    }
    outLen = end;
    out[outLen++] = '"';
  }

  void writeEscapedFrom(String s, int from) {
    int len = s.length();
    for (int i = from; i < len; ++i) {
      char ch = s.charAt(i);
      String escape = ch < 128 ? ESCAPES[ch] : null;
      if (escape == null) {
        put(ch);
      } else {
        put(escape);
      }
    }
  }

  void writeChar(Character c) {
    char ch = c;
    put('\\');
    String name = ch < CHAR_NAMES.length ? CHAR_NAMES[ch] : null;
    if (name == null) {
      put(ch);
    } else {
      put(name);
    }
  }


  /////////////
  // Numbers //
  /////////////

  static int digitCount(long value) { // value >= 0
    long limit = 10;
    for (int count = 1; count < 19; count++) {
      if (value < limit) {
        return count;
      }
      limit *= 10;
    }
    return 19;
  }

  /**
   * Same digits as Long.toString, without the String: written into the buffer from the last digit backwards,
   * two digits per division by 100; int arithmetic (faster) once the value fits in an int.
   */
  void writeLong(long value) {
    if (value == Long.MIN_VALUE) { // -value would overflow
      put("-9223372036854775808");
      return;
    }
    ensureCapacity(20);
    char[] out = this.out;
    if (value < 0) {
      out[outLen++] = '-';
      value = -value;
    }
    int end = outLen + digitCount(value);
    int at = end;
    while (value > Integer.MAX_VALUE) {
      long quotient = value / 100;
      int pairIndex = (int) (value - quotient * 100) * 2;
      out[--at] = DIGIT_PAIRS[pairIndex + 1];
      out[--at] = DIGIT_PAIRS[pairIndex];
      value = quotient;
    }
    int rest = (int) value;
    while (rest >= 100) {
      int quotient = rest / 100;
      int pairIndex = (rest - quotient * 100) * 2;
      out[--at] = DIGIT_PAIRS[pairIndex + 1];
      out[--at] = DIGIT_PAIRS[pairIndex];
      rest = quotient;
    }
    if (rest >= 10) {
      out[--at] = DIGIT_PAIRS[2 * rest + 1];
      out[--at] = DIGIT_PAIRS[2 * rest];
    } else {
      out[--at] = (char) ('0' + rest);
    }
    outLen = end;
  }

  void writeDouble(Double d) {
    writeDouble(d.doubleValue(), d);
  }

  /** boxed: the Double or Float itself; its toString is the EDN form of finite values */
  void writeDouble(double value, Object boxed) {
    if (value == Double.POSITIVE_INFINITY) {
      put("##Inf");
    } else if (value == Double.NEGATIVE_INFINITY) {
      put("##-Inf");
    } else if (Double.isNaN(value)) {
      put("##NaN");
    } else {
      put(boxed.toString());
    }
  }


  //////////////////
  // #inst #uuid //
  //////////////////

  // Dates written directly: years 1583-9999. There java.time (proleptic Gregorian) and clojure.instant's
  // SimpleDateFormat (Julian calendar before 1582) agree, and the year has 4 digits. Other dates go through
  // print-method.
  static final long INST_FAST_MIN = java.time.LocalDateTime.of(1583, 1, 1, 0, 0).toEpochSecond(java.time.ZoneOffset.UTC) * 1000;
  static final long INST_FAST_END = java.time.LocalDateTime.of(10000, 1, 1, 0, 0).toEpochSecond(java.time.ZoneOffset.UTC) * 1000;

  /** Same format as clojure.instant: #inst "yyyy-MM-dd'T'HH:mm:ss.SSS-00:00", in UTC */
  void writeInst(java.util.Date date) {
    long ms = date.getTime();
    if (ms < INST_FAST_MIN || ms >= INST_FAST_END) {
      callPrintMethod(date);
      return;
    }
    java.time.LocalDateTime time = java.time.LocalDateTime.ofEpochSecond(
      Math.floorDiv(ms, 1000L), (int) Math.floorMod(ms, 1000L) * 1000000, java.time.ZoneOffset.UTC);
    ensureCapacity(40);
    put("#inst \"");
    writeZeroPadded(time.getYear(), 4);       put('-');
    writeZeroPadded(time.getMonthValue(), 2); put('-');
    writeZeroPadded(time.getDayOfMonth(), 2); put('T');
    writeZeroPadded(time.getHour(), 2);       put(':');
    writeZeroPadded(time.getMinute(), 2);     put(':');
    writeZeroPadded(time.getSecond(), 2);     put('.');
    writeZeroPadded((int) Math.floorMod(ms, 1000L), 3);
    put("-00:00\"");
  }

  /** Caller ensures capacity */
  void writeZeroPadded(int value, int width) {
    for (int i = width - 1; i >= 0; --i) {
      out[outLen + i] = (char) ('0' + value % 10);
      value /= 10;
    }
    outLen += width;
  }


  /////////////////
  // Collections //
  /////////////////

  /** Walks the vector's internal arrays of 32 elements instead of calling nth per element */
  void writeVector(PersistentVector v) {
    put('[');
    int n = v.count();
    for (int i = 0; i < n; i += 32) {
      Object[] chunk = v.arrayFor(i);
      int chunkLen = Math.min(32, n - i);
      for (int j = 0; j < chunkLen; ++j) {
        if (i + j > 0) {
          put(' ');
        }
        writeValue(chunk[j]);
      }
    }
    put(']');
  }

  /** Vectors other than PersistentVector: subvec, MapEntry, ... */
  void writeOtherVector(IPersistentVector v) {
    put('[');
    int n = v.count();
    for (int i = 0; i < n; ++i) {
      if (i > 0) {
        put(' ');
      }
      writeValue(v.nth(i));
    }
    put(']');
  }

  void writeSet(java.util.Collection s) {
    put('#');
    put('{');
    boolean first = true;
    for (Object o : s) {
      if (first) {
        first = false;
      } else {
        put(' ');
      }
      writeValue(o);
    }
    put('}');
  }

  void writeSeq(ISeq s) {
    put('(');
    boolean first = true;
    for (ISeq xs = RT.seq(s); xs != null; xs = xs.next()) {
      if (first) {
        first = false;
      } else {
        put(' ');
      }
      writeValue(xs.first());
    }
    put(')');
  }

  /** Through kvreduce: no MapEntry or seq node per entry. Same entry order as seq. */
  void writeMap(IKVReduce m) {
    put('{');
    EntryWriter entries = entryWriter;
    boolean outerFirstEntry = entries.firstEntry;
    entries.firstEntry = true;
    m.kvreduce(entries, null);
    entries.firstEntry = outerFirstEntry;
    put('}');
  }

  /** The function kvreduce calls per map entry */
  final class EntryWriter extends AFn {
    boolean firstEntry;

    @Override
    public Object invoke(Object acc, Object k, Object v) {
      if (firstEntry) {
        firstEntry = false;
      } else {
        put(',');
        put(' ');
      }
      writeValue(k);
      put(' ');
      writeValue(v);
      return acc;
    }
  }
}
