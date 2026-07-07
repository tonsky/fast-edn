### 1.2.0 - July 7, 2026

Fixed number parsing edge cases:

- Cannot read number ending with # #7
- Nested octal numbers are parsed as decimal #11
- Octal numbers ending in delimiter parsed as decimal #22
- `0-1` and `0+1` are recognized as numbers #13
- Nested large number is parsed incorrectly #14
- Large number followed by `,` parsed incorrectly #21
- Cannot parse leading zero after radix #25
- Cannot parse `25RN` #26

Treat `;` `^` and control chars as token boundaries:

- Unprintable ASCII codes recognized as forms #12
- Trailing line comment included in symbol or keyword #19
- Trailing `^` included in symbols and keywords #20
- Cannot parse character followed by `^` or `;` #23

Various other edge cases:

- Overly permissive handling of `:` in keywords and symbols #9
- Syntax quote, `~`, `@`, `~@` interpreted as start of symbol #16
- Cannot handle whitespace before namespace map namespace #18
- Invalid parsing of symbolic value followed by non-whitespace #10
- Insufficient error checking of octal escape sequences #8
- Don’t invoke tag handlers inside `#_` #28

And improved error reporting.

### 1.1.3 - May 4, 2025

- Fix parsing `-` and `+` before delim and `UnexpectedCharacter` leaks #6 via @frenchy64 @ericnormand @balloneij @dpetranek @sivakusayan @brunchboy @djwhitt

### 1.1.2 - Jan 5, 2025

- Fixed reading `)` `]` `}` after line comment #3

### 1.1.1 - Dec 30, 2024

- Fixed nested maps reading #2

### 1.1.0 - Dec 20, 2024

- Remove `read`, add `read-once` and `read-next` #1

### 1.0.0 - Dec 19, 2024

- Initial