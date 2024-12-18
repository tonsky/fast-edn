# Fast EDN parser

Fast EDN is an EDN parser that is roughly 6 times faster than clojure.edn:

| Test file             | clojure.edn | fast-edn.core | speed up, times |
| :---                  |        ---: |          ---: |            ---: |
| edn_basic_10.edn      |       0.504 |         0.277 |          ×  1.8 |
| edn_basic_100.edn     |       3.040 |         0.534 |          ×  5.7 |
| edn_basic_1000.edn    |      19.495 |         2.733 |          ×  7.1 |
| edn_basic_10000.edn   |     221.773 |        36.887 |          ×  6.0 |
| edn_basic_100000.edn  |    2138.255 |       356.772 |          ×  6.0 |
| edn_nested_100000.edn |    2585.372 |       441.200 |          ×  5.9 |
| ints_1400.edn         |     431.432 |        27.000 |          × 16.0 |
| keywords_10.edn       |       3.961 |         0.634 |          ×  6.2 |
| keywords_100.edn      |      34.980 |         4.848 |          ×  7.2 |
| keywords_1000.edn     |     369.404 |        53.942 |          ×  6.8 |
| keywords_10000.edn    |    4168.732 |       654.090 |          ×  6.4 |
| strings_1000.edn      |     651.043 |        42.335 |          × 15.4 |
| strings_uni_250.edn   |     641.900 |       102.268 |          ×  6.3 |

Fast EDN achieves JSON parsing speeds (json + keywordize keys vs EDN of the same size):

| File size | cheshire | jsonista | charred | fast-edn |
| :---      |     ---: |     ---: |    ---: |      --: |
| 10        |    0.588 |    0.137 |   0.328 |    0.277 |
| 100       |    1.043 |    0.594 |   0.721 |    0.534 |
| 1K        |    4.224 |    2.999 |   3.016 |    2.733 |
| 10K       |   37.793 |   34.374 |  32.623 |   36.887 |
| 100K      |  359.558 |  327.997 | 313.280 |  356.772 |

We also ship with `#inst` parser that is up to 15 times faster:

```
Benchmarking (clojure.instant/read-instant-date "2024-12-17T15:54:00.000+01:00")
└╴Mean time: 1.553710 µs, alloc: 4.19 KB, stddev: 25.982112 ns, calls: 64617

Benchmarking (fast-edn.core/read-instant-date "2024-12-17T15:54:00.000+01:00")
└╴Mean time: 93.173277 ns, alloc: 0.07 KB, stddev: 1.453980 ns, calls: 1041000
```

All execution times above are in µs, M1 Pro CPU, JDK Zulu23.30+13-CA.

To run benchmarks yourself:

```sh
./script/bench_json.sh
./script/bench_edn.sh
```

## Using

Add this to `deps.edn`:

```clojure
io.github.tonsky/fast-edn {:mvn/version "1.0.0"}
```

API is a drop-in replacement for `clojure.edn`:

```clojure
(require '[fast-edn.core :as edn])

;; Read from string
(edn/read-string "{:a 1}")

;; Read from java.io.Reader
(edn/read (FileReader. "data.edn"))

;; Options
(edn/read {:eof     ::eof
           :readers {'inst #(edn/parse-timestamp edn/construct-instant %)}
           :default (fn [tag value]
                      (clojure.core/tagged-literal tag value))})
```

In addition, `fast-edn.core/read` directly supports `InputStream`, `File`, `byte[]`, `char[]` and `String`:

```clojure
(edn/read (io/file "data.edn"))
```

There’s also an option to report line number/column instead of offset if parsing fails (at the cost of a little performance):

```clojure
(read-string "\"abc")
; => RuntimeException: EOF while reading string: "abc, offset: 4, context:
;    "abc
;       ^

(read-string {:count-lines true} "\"abc")
; => RuntimeException: EOF while reading string: "abc, line: 1, column: 5, offset: 4, context:
;    "abc
;       ^
```

## Compatibility

Fast EDN is 100% compatible with clojure.edn. It will read everything that clojure.edn would.

Most cases that clojure.edn rejects, Fast EDN will reject too. There are some exceptions though: Fast EDN is a tiny bit more permissive than clojure.edn. We tried to follow intent and just simplify/streamline edge cases where it made sense.

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

## Testing

Fast EDN is extensively tested by test suite from clojure.core, by our own generative test suite and by a set of hand-crafted test cases.

## License

Copyright © 2024 Nikita Prokopov

Licensed under [MIT](LICENSE).
