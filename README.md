# Fast EDN parser

> EDN format is very similar to JSON, thus it should parse as fast as JSON.

Fast EDN is a drop-in replacement for `clojure.edn/read-string` that is roughly 6 times faster:

| Test file         | clojure.edn | fast-edn.core | speed up, times |
| :---              |        ---: |          ---: |            ---: |
| basic_10          |       0.504 |         0.290 |          ×  1.7 |
| basic_100         |       3.040 |         0.594 |          ×  5.1 |
| basic_1000        |      19.495 |         2.815 |          ×  6.9 |
| basic_10000       |     221.773 |        37.560 |          ×  5.9 |
| basic_100000      |    2138.255 |       370.045 |          ×  5.8 |
| ints_1400         |     431.432 |        33.164 |          × 13.0 |
| keywords_10       |       3.961 |         0.625 |          ×  6.3 |
| keywords_100      |      34.980 |         4.769 |          ×  7.3 |
| keywords_1000     |     369.404 |        53.943 |          ×  6.8 |
| keywords_10000    |    4168.732 |       662.099 |          ×  6.3 |
| nested_100000     |    2585.372 |       503.644 |          ×  5.1 |
| strings_1000      |     651.043 |        40.455 |          × 16.1 |
| strings_uni_250   |     641.900 |       108.341 |          ×  5.9 |

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
| basic_10     |       0.481 |           2.832 |        1.474 |      0.290 |
| basic_100    |       2.799 |           4.242 |        2.297 |      0.594 |
| basic_1000   |      17.548 |          14.738 |        6.583 |      2.815 |
| basic_10000  |     211.536 |         125.741 |       46.849 |     37.560 |
| basic_100000 |    2016.885 |        1167.972 |      447.013 |    370.045 |

All execution times above are in µs, M1 Pro 16 Gb, single thread, JDK Zulu23.30+13-CA.

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
io.github.tonsky/fast-edn {:mvn/version "1.1.3"}
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

Fast EDN is 100% compatible with clojure.edn. It will read everything that clojure.edn would.

Most cases that clojure.edn rejects, Fast EDN will reject too. There are some minor exceptions though: Fast EDN is a tiny bit more permissive than clojure.edn. We tried to follow intent and just simplify/streamline edge cases where it made sense.

In Fast EDN, ratios can be specified with arbitrary integers:

```clojure
(clojure.edn/read-string "2r1111N")
; => NumberFormatException: For input string: "1111N" under radix 2

(fast-edn.core/read-string "2r1111N")
; => 15N

(clojure.edn/read-string "0xFF/0x02")
; => NumberFormatException: Invalid number: 0xFF/0x02

(fast-edn.core/read-string "0xFF/0x02")
; => 255/2
```

Symbols/keywords can have slashes anywhere, first slash is ns separator. Clojure allows them _almost_ anywhere but rules for when it doesn’t are _weird_:

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

Copyright © 2024 Nikita Prokopov

Licensed under [MIT](LICENSE).
