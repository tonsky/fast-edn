# Fast EDN parser

> EDN format is very similar to JSON, thus it should parse as fast as JSON.

Fast EDN is a drop-in replacement for `clojure.edn/read-string` that is roughly 5-7 times faster:

| Test file         | clojure.edn | fast-edn.core | speed up, times |
| :---              |        ---: |          ---: |            ---: |
| basic_10          |       0.314 |         0.222 |          ×  1.4 |
| basic_100         |       1.773 |         0.414 |          ×  4.3 |
| basic_1000        |      11.075 |         2.063 |          ×  5.4 |
| basic_10000       |     131.895 |        27.183 |          ×  4.9 |
| basic_100000      |    1282.128 |       262.327 |          ×  4.9 |
| ints_1400         |     267.529 |        16.955 |          × 15.8 |
| keywords_10       |       2.431 |         0.443 |          ×  5.5 |
| keywords_100      |      21.885 |         3.084 |          ×  7.1 |
| keywords_1000     |     243.727 |        34.807 |          ×  7.0 |
| keywords_10000    |    2686.706 |       418.102 |          ×  6.4 |
| nested_100000     |    1570.673 |       320.779 |          ×  4.9 |
| strings_1000      |     337.831 |        30.289 |          × 11.2 |
| strings_uni_250   |     334.157 |        48.338 |          ×  6.9 |

Fast EDN achieves JSON parsing speeds (json + keywordize keys vs EDN of the same size):

| File size    | cheshire | jsonista | charred | fast-edn |
| :---         |     ---: |     ---: |    ---: |      --: |
| basic_10     |    0.588 |    0.137 |   0.328 |    0.290 |
| basic_100    |    1.043 |    0.594 |   0.721 |    0.594 |
| basic_1000   |    4.224 |    2.999 |   3.016 |    2.815 |
| basic_10000  |   37.793 |   34.374 |  32.623 |   37.560 |
| basic_100000 |  359.558 |  327.997 | 313.280 |  370.045 |

Speed of EDN parsing makes Transit obsolete on JVM:

| file         | clojure.edn | transit+msgpack | transit+json |   fast-edn |
| :---         |        ---: |            ---: |         ---: |       ---: |
| basic_10     |       0.318 |           1.524 |        1.716 |      0.228 |
| basic_100    |       2.097 |           1.963 |        2.186 |      0.436 |
| basic_1000   |      12.275 |           4.394 |        5.238 |      2.161 |
| basic_10000  |     132.793 |          27.562 |       35.861 |     28.806 |
| basic_100000 |    1258.169 |         244.902 |      334.743 |    277.867 |

All execution times above are in µs, M4 Air 32 Gb, single thread, JDK Temurin-26.0.1+8.

To run benchmarks yourself:

```sh
./script/bench_json.sh
./script/bench_edn.sh
./script/bench_transit.sh
```

## Other benefits

Fast EDN has more consistent error reporting. Clojure:

```clojure
(clojure.edn/read-string "1a")
; => NumberFormatException: Invalid number: 1a

(clojure.edn/read-string "{:a 1 :b")
; => RuntimeException: EOF while reading

(clojure.edn/read-string "\"{:a 1 :b")
; => RuntimeException: EOF while reading string

(clojure.edn/read-string "\"\\u123\"")
; => IllegalArgumentException: Invalid character length: 3, should be: 4
```

Fast EDN includes location information in exceptions:

```clojure
(fast-edn.core/read-string "1a")
; => NumberFormatException: For input string: "1a", offset: 2, context:
;    1a
;     ^

(fast-edn.core/read-string "{:a 1 :b")
; => RuntimeException: Map literal must contain an even number of forms: {:a 1, :b, offset: 8, context:
;    {:a 1 :b
;           ^

(fast-edn.core/read-string "\"{:a 1 :b")
; => RuntimeException: EOF while reading string: "{:a 1 :b, offset: 9, context:
;    "{:a 1 :b
;            ^

(fast-edn.core/read-string "\"\\u123\"")
; => RuntimeException: Unexpected digit: ", offset: 7, context:
;    "\u123"
;          ^
```

Optionally, you can include line number/column information at the cost of a little performance:

```clojure
(read-string {:count-lines true} "\"abc")
; => RuntimeException: EOF while reading string: "abc, line: 1, column: 5, offset: 4, context:
;    "abc
;       ^
```

## Using

Add this to `deps.edn`:

```clojure
io.github.tonsky/fast-edn {:mvn/version "1.2.0"}
```

`read-string` works exactly the same as in `clojure.edn`:

```clojure
(require '[fast-edn.core :as edn])

;; Read from string
(edn/read-string "{:a 1}")

;; Options
(edn/read-string
  {:eof     ::eof
   :readers {'inst #(edn/parse-timestamp edn/construct-instant %)}
   :default (fn [tag value]
              (clojure.core/tagged-literal tag value))})
```

In addition to strings, `fast-edn.core/read-once` allows you to read from `InputStream`, `File`, `byte[]`, `char[]` and `String`:

```clojure
(edn/read-once (io/file "data.edn"))
```

Note that `read-once` closes the Reader/InputStream you pass to it, so it’s not a direct analogue of `clojure.edn/read`.

Consuming multiple sequential objects from the same Reader/InputStream is possible but looks slightly different. In Clojure:

```clojure
(let [r (java.io.PushbackReader. reader)]
  (take-while #(not= ::eof %)
    (repeatedly #(clojure.edn/read {:eof ::eof} r))))
```

In Fast EDN:

```clojure
(let [p (fast-edn.core/parser {:eof ::eof} reader)]
  (take-while #(not= ::eof %)
    (repeatedly #(fast-edn.core/read-next p))))
```

## Compatibility

Fast EDN would read almost 100% of what clojure.edn would. Exceptions to that rule are edge cases:

- keywords without nameaspace (`:/kw`),
- symbols that begin with quoute (`'sym`, clojure.edn includes the quotation mark as part of the symbols’ name),
- leading zeros in ration (`08/1` in Clojure reads as 8, we treat `0` as an octal marker).

We also _extend_ what can be read where it made sense/made things more consistent. For example, in Fast EDN, ratios can be specified with arbitrary integers:

```clojure
(clojure.edn/read-string "0xFF/0x02")
; => NumberFormatException: Invalid number: 0xFF/0x02

(fast-edn.core/read-string "0xFF/0x02")
; => 255/2

(clojure.edn/read-string "2r1111N")
; => NumberFormatException: For input string: "1111N" under radix 2

(fast-edn.core/read-string "2r1111N")
; => 15N
```

Symbols/keywords can have slashes anywhere, first slash is ns separator. Clojure allows them _almost_ anywhere but rules for when it doesn’t are inconsistent:

```clojure
(clojure.edn/read-string ":ns/sym/")
; => RuntimeException: Invalid token: :ns/sym/

(read-string ":ns/sym/")
; => :ns/sym/
```

Same goes for keywords starting with a number. Clojure allows `:1a` but not `:ns/1a` and it seems like an oversight rather than a deliberate design decision:

```clojure
(clojure.edn/read-string ":ns/1a")
; => RuntimeException: Invalid token: :ns/1a

(fast-edn.core/read-string ":ns/1a")
; => :ns/1a
```

We also support vectors in metadata since Clojure supports them and EDN parser was probably just not updated in time.

```clojure
(clojure.edn/read-string "^[tag] {}")
; => IllegalArgumentException: Metadata must be Symbol,Keyword,String or Map

(fast-edn.core/read-string "^[tag] {}")
; => {:param-tags ['tag]} {}
```

According to [github.com/edn-format/edn](https://github.com/edn-format/edn), metadata should not be handled by EDN at all, but `clojure.edn` supports it and so are we.

## Test coverage

Fast EDN is extensively tested by test suite from clojure.core, by our own generative test suite and by a set of hand-crafted test cases.

To run tests yourself:

```sh
./script/test.sh
```

## What’s the secret?

Fast EDN achieves its speed mainly by avoiding two things clojure.edn does:

- reading from Reader one char at a time,
- using regexps.

## Appreciation

- [charred](https://github.com/cnuernber/charred) for starting point
- [clj-async-profiler](https://github.com/clojure-goes-fast/clj-async-profiler) and
[criterium](https://github.com/hugoduncan/criterium/) for providing the tools

## License

Copyright © 2024-2026 Nikita Prokopov

Licensed under [MIT](LICENSE).
