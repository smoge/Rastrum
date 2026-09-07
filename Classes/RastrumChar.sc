// RastrumChar: readable ASCII punctuation for parser code.
//
// These avoid dollar character literals for quotes and backslashes,
// which are valid sclang but confuse syntax highlighting in text
// editors.
RastrumChar {
    classvar <doubleQuote, <singleQuote, <backslash;

    *initClass {
        doubleQuote = 34.asAscii;
        singleQuote = 39.asAscii;
        backslash   = 92.asAscii;
    }
}
