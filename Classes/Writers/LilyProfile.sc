// LilyPond document policy is wider than `\layout`. A profile writes
// into header, preamble, paper, layout, score and staff destinations.
// `prSpelling` keeps the key-to-destination map close to the code.


// A profile is a base name plus checked settings. The key set is
// closed and values are typed. What this vocabulary doesn't name
// stays fixed by the base.
//
// >>> LilyProfile.complexRhythm.at(\proportionalNotationDuration) -> Duration(1/16)
// >>> LilyProfile.openComplexRhythm.at(\markupSystemPadding)      -> 3
// >>> LilyProfile.from(\default).name                             -> default
// >>> try { LilyProfile(\spacious) } { \refused }
// refused


// LilyProfile: the engraving policy one LilyPond document carries.
//
// Owned by LilyWriter and named nowhere else.
LilyProfile {
    classvar <names, <settingKeys, <paperSizes, <tupletNumberTexts;
    // No getter: callers choose from `paperSizes`.
    classvar paperArguments;

    var <name, settings;

    *initClass {
        names = [
            \default,
            \complexRhythm,
            \landscapeComplexRhythm,
            \openComplexRhythm
        ];

        settingKeys = [
            \proportionalNotationDuration,
            \staffSize,
            \paperSize,
            \tagline,
            \raggedRight,
            \systemSpacing,
            \markupSystemPadding,
            \staffSpacing,
            \tupletNumberText,
            \bracketPadding,
            \horizontalBrackets,
            \indent
        ];

        paperSizes = [\letter, \a4, \letterLandscape, \a4Landscape];

        paperArguments = IdentityDictionary[
            \letter          -> "letter",
            \a4              -> "a4",
            \letterLandscape -> "letterlandscape",
            \a4Landscape     -> "a4landscape"
        ];
        tupletNumberTexts = [\fraction, \count];
    }

    *new { |name = \default, settings| ^super.new.init(name, settings) }

    *default      { |settings| ^this.new(\default, settings) }
    *complexRhythm { |settings| ^this.new(\complexRhythm, settings) }
    *landscapeComplexRhythm { |settings|
        ^this.new(\landscapeComplexRhythm, settings)
    }
    *openComplexRhythm { |settings| ^this.new(\openComplexRhythm, settings) }

    // Normalize a profile argument.
    *from { |layout|
        if (layout.isKindOf(LilyProfile)) { ^layout };
        if (layout.isKindOf(Symbol) or: { layout.isKindOf(String) }) {
            ^this.new(layout.asSymbol)
        };
        Error(
            "LilyProfile: profile must be a name or LilyProfile, got %.".format(layout.class)
        ).throw
    }

    init { |argName, argSettings|
        name = this.class.checkedName(argName);
        settings = this.class.prDefaults(name);
        this.class.checkedSettings(argSettings).keysValuesDo { |key, value|
            settings[key] = value
        };
        ^this
    }

    // A copy, so a built profile cannot be edited under the writer.
    settings { ^settings.copy }

    at { |key| ^settings[key.asSymbol] }


    // --- what a base fixes ---

    // Note [Rhythm profiles share one layout policy]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // Rhythm presets share one `\layout` body so tuplets don't drift
    // between portrait, landscape and open rhythm pages.
    //
    // The base room is part of that policy: `indent: 12` leaves a
    // readable gap for staff names, `indent: 0` asks for edge-to-edge
    // systems and `systemSpacing: 16` leaves room above full-length
    // tuplets and ratio text.
    //
    // `landscapeComplexRhythm` is the same rhythm on a wider stock,
    // with a smaller staff and no LilyPond tagline.
    // `openComplexRhythm` starts there and changes only the values
    // that make horizontal bracket towers readable.
    //
    // `staffSpacing` writes grouped and bare-staff overrides.
    // Single-staff system gaps are `systemSpacing`.
    // `minimum-distance` matches `basic-distance`, so page breaking
    // keeps the clearance instead of compressing it away.

    // Defaults a caller may replace.
    //
    // Literal base text lives in `prBody`.
    *prDefaults { |name|
        var rhythm;

        if (this.prIsRhythmProfile(name).not) { ^() };

        rhythm = (
            proportionalNotationDuration: Duration(1, 16),
            tupletNumberText: \fraction,
            bracketPadding: 2,
            // room for staff names in rhythm presets
            indent: 12,
            // room above full-length tuplets and ratio text
            systemSpacing: 16
        );

        if (name == \complexRhythm) { ^rhythm };
        // same rhythm settings, wider page, smaller staff
        //
        // See Note [Rhythm profiles share one layout policy].
        rhythm[\paperSize] = \letterLandscape;
        rhythm[\staffSize] = 12;
        // suppress the tagline only here
        rhythm[\tagline]   = false;

        if (name == \landscapeComplexRhythm) { ^rhythm };

        // Wider spacing for bracket towers.
        // The failure is horizontal first.
        //
        // See Note [Rhythm profiles share one layout policy].
        rhythm[\proportionalNotationDuration] = Duration(1, 60);
        rhythm[\markupSystemPadding]          = 3;
        rhythm[\bracketPadding]               = 2.4;
        rhythm[\horizontalBrackets]           = true;
        ^rhythm
    }

    // Every rhythm profile spells the same `\layout` body.
    *prIsRhythmProfile { |name|
        ^[\complexRhythm, \landscapeComplexRhythm, \openComplexRhythm]
            .includes(name)
    }

    // A base's body for one destination. Strings are fixed lines. Symbols mark
    // setting positions.
    //
    // Unplaced settings are appended in vocabulary order.
    *prBody { |name, destination|

        if (this.prIsRhythmProfile(name).not) { ^[] };
        if (destination == \layout)           { ^[\indent] };
        if (destination != \score)            { ^[] };

        ^[
            \proportionalNotationDuration,
            "tupletFullLength = ##t",
            "tupletFullLengthNote = ##f",
            \tupletNumberText,
            "\\override TupletBracket.bracket-visibility = ##t",
            "\\override TupletBracket.full-length-to-extent = ##f",
            "\\override TupletBracket.shorten-pair = #'(-0.2 . 0.35)",
            \bracketPadding,
            \horizontalBrackets,
            "\\override TupletBracket.minimum-length = #3",
            "\\override TupletBracket.springs-and-rods = #ly:spanner::set-spacing-rods"
        ]
    }


    // ------ spelling ------

    // The two contexts `staffSpacing` has to spell.
    //
    // See Note [Rhythm profiles share one layout policy].
    *prSpacing { |grob, value|
        ^[
            "\\override %.staff-staff-spacing.basic-distance = #%".format(grob, value),
            "\\override %.staff-staff-spacing.minimum-distance = #%".format(grob, value)
        ]
    }

    // Note [Landscape is a stock, not a rotation]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // A wide page is a paper stock with the orientation in its name,
    // such as `letterlandscape`. LilyPond's orientation switches
    // rotate the printout. They don't lay the music out across a wide
    // stock.

    // Every destination one key writes into.
    //
    // `systemSpacing` reaches `system-system-spacing.basic-distance`.
    // `markupSystemPadding` reaches the title-to-system skyline gap.
    *prSpelling { |key, value|
        ^switch (key,
            \proportionalNotationDuration, {
                (score: ["proportionalNotationDuration = #%/%".format(value.numerator, value.denominator)])
            },
            \staffSize, {
                (preamble: ["#(set-global-staff-size %)".format(value)])
            },
            \paperSize, {
                (preamble: ["#(set-default-paper-size \"%\")".format(paperArguments[value])])
            },
            \tagline, { (header: ["tagline = ##f"]) },
            \raggedRight, {
                var line = "ragged-right = " ++ if (value) { "##t" } { "##f" };
                (paper: [line], layout: [line])
            },
            \systemSpacing, {
                (paper: ["system-system-spacing.basic-distance = #%".format(value)])
            },
            \markupSystemPadding, {
                (paper: ["markup-system-spacing.padding = #%".format(value)])
            },
            \staffSpacing, {
                (
                    score: this.prSpacing("StaffGrouper", value),
                    staff: this.prSpacing("VerticalAxisGroup", value)
                )
            },
            \tupletNumberText, {
                (score: ["\\override TupletNumber.text = #tuplet-number::calc-%-text"
                    .format(if (value == \fraction) { "fraction" } { "denominator" })])
            },
            \bracketPadding, {
                (score: ["\\override TupletBracket.padding = #%".format(value)])
            },
            \horizontalBrackets, {
                (score: ["\\override TupletBracket.max-slope-factor = #%"
                    .format(if (value) { 0 } { 0.5 })])
            },
            // room for staff names in rhythm presets
            //
            // See Note [Rhythm profiles share one layout policy].
            \indent, { (layout: ["indent = #%".format(value)]) }
        )
    }

    *prLines { |key, value, destination|
        if (value.isNil) { ^[] };
        ^this.prSpelling(key, value)[destination] ?? { [] }
    }

    // Lines for one destination: base body, then unplaced settings.
    prLinesFor { |destination|
        var placed = IdentitySet.new;
        var lines = [];
        this.class.prBody(name, destination).do { |entry|
            if (entry.isKindOf(Symbol)) {
                placed.add(entry);
                lines = lines ++ this.class.prLines(entry, settings[entry], destination)
            } {
                lines = lines.add(entry)
            }
        };
        this.class.settingKeys.do { |key|
            if (placed.includes(key).not) {
                lines = lines ++ this.class.prLines(key, settings[key], destination)
            }
        };
        ^lines
    }

    // ---- what the writer asks for ---

    // Header lines only.
    //
    // `LilyWriter` merges score metadata and writes braces.
    headerLines { ^this.prLinesFor(\header) }

    // Everything above `\score`; "" when there is none.
    preambleString {
        var top = this.prLinesFor(\preamble);
        var paper = this.prLinesFor(\paper);
        var out = "";
        if (top.isEmpty and: { paper.isEmpty }) { ^"" };
        top.do { |line| out = out ++ line ++ "\n" };
        if (paper.notEmpty) {
            if (top.notEmpty) { out = out ++ "\n" };
            out = out ++ "\\paper {\n";
            paper.do { |line| out = out ++ "  " ++ line ++ "\n" };
            out = out ++ "}\n";
        };
        ^out ++ "\n"
    }

    // The block inside `\score`, indented like the writer's other blocks.
    layoutString {
        var body  = this.prLinesFor(\layout);
        var score = this.prLinesFor(\score);
        var staff = this.prLinesFor(\staff);
        var out;
        if (body.isEmpty and: { score.isEmpty } and: { staff.isEmpty }) {
            ^"  \\layout { }\n"
        };
        out = "  \\layout {\n";
        body.do { |line| out = out ++ "    " ++ line ++ "\n" };
        out = out ++ this.prContextString("Score", score)
                  ++ this.prContextString("Staff", staff);
        ^out ++ "  }\n"
    }

    prContextString { |context, lines|
        var out;
        if (lines.isEmpty) { ^"" };
        out = "    \\context {\n      \\" ++ context ++ "\n";
        lines.do { |line| out = out ++ "      " ++ line ++ "\n" };
        ^out ++ "    }\n"
    }

    // --- refusals ---

    // Refuse profile names.
    *checkedName { |name|
        if (names.includes(name.asSymbol).not) {
            Error(
                "LilyProfile: % is not a profile. Profiles: %."
                .format(name.asCompileString, names.join(", "))
            ).throw
        };
        ^name.asSymbol
    }

    *checkedSettings { |dict|
        var out = ();
        if (dict.isNil) { ^out };
        if (dict.isKindOf(Dictionary).not) {
            Error(
                "LilyProfile: settings must be a Dictionary, got %."
                .format(dict.class)
            ).throw
        };
        dict.keysValuesDo { |key, value|
            out[key.asSymbol] = this.checkedSetting(key.asSymbol, value)
        };
        ^out
    }

    // Every setting key and value is checked before spelling.
    *checkedSetting { |key, value|
        if (settingKeys.includes(key).not) {
            Error(
                "LilyProfile: % is not a setting. Settings: %."
                .format(key.asCompileString, settingKeys.join(", "))
            ).throw
        };
        switch (key,
            \proportionalNotationDuration, {
                // This slot takes exact Duration objects only.
                if (value.isKindOf(Duration).not) {
                    Error(
                        "LilyProfile: proportionalNotationDuration must be a Duration, got %."
                        .format(value.class)
                    ).throw
                };
                if ((value > 0).not) {
                    Error(
                        "LilyProfile: proportionalNotationDuration must be positive, got %."
                        .format(value)
                    ).throw
                }
            },
            \paperSize,        { this.prOneOf(key, value, paperSizes) },
            \tupletNumberText, { this.prOneOf(key, value, tupletNumberTexts) },
            \raggedRight, { this.prBoolean(key, value) },

            // Zero means full page width.
            // Below zero starts off the page.
            \indent, {
                if (value.isNumber.not or: { value < 0 }) {
                    Error(
                        "LilyProfile: indent is a distance of zero or more, got %."
                        .format(value.asCompileString)
                    ).throw
                }
            },
            \horizontalBrackets, { this.prBoolean(key, value) },

            // `false` suppresses LilyPond's credit. Omission leaves
            // it alone. Custom tagline text is a different feature,
            // not half-admitted.
            \tagline, {
                if (value != false) {
                    Error(
                        "LilyProfile: tagline only takes false. Omit the key to keep "
                        "LilyPond's credit line. Got %.".format(value.asCompileString)
                    ).throw
                }
            },
            // Distances and staff size: positive, with no guessed
            // upper bound.
            { this.prPositive(key, value) }
        );
        ^value
    }

    *prBoolean { |key, value|
        if (value.isKindOf(Boolean).not) {
            Error(
                "LilyProfile: % must be true or false, got %."
                .format(key, value.class)
            ).throw
        }
    }

    *prOneOf { |key, value, allowed|
        if (value.isKindOf(Symbol).not or: { allowed.includes(value).not }) {
            Error(
                "LilyProfile: % is not a valid %. Values: %."
                .format(value.asCompileString, key, allowed.join(", "))
            ).throw
        }
    }

    *prPositive { |key, value|
        if (value.isNumber.not) {
            Error(
                "LilyProfile: % must be a number, got %."
                .format(key, value.class)
            ).throw
        };
        if (value <= 0) {
            Error("LilyProfile: % must be positive, got %.".format(key, value)).throw
        }
    }

    printOn { |stream|
        stream << "LilyProfile(" << name;
        if (settings.notEmpty) { stream << ", " << settings };
        stream << ")";
    }
}
