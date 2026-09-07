// A part, its bars in order, name and opening clef.
// The clef belongs here. Bar-local clefs live on `Measure`.
Staff : ScoreContainer {
    // Clefs the page writers can spell, with MusicXML sign and line.
    //
    // Closed because MusicXML needs `<sign>` and `<line>`.
    //
    // Octave-transposing clefs are a second fact, not more clef names.
    classvar <clefSigns;

    var <>name, <clef, <shortName;

    *initClass {
        clefSigns = IdentityDictionary[
            \treble -> ["G", 2],
            \bass   -> ["F", 4],
            \alto   -> ["C", 3],
            \tenor  -> ["C", 4],
            \percussion -> ["percussion", 2]
        ];
    }

    // >>> Staff.clefs   -> [ alto, bass, percussion, tenor, treble ]
    *clefs { ^clefSigns.keys.asArray.sort }

    // `nil` means "say nothing."
    //
    // >>> Staff.checkedClef(nil)   -> nil
    *checkedClef { |value|
        if (value.isNil) { ^nil };
        if (clefSigns[value.asSymbol].isNil) {
            Error(
                "Staff: % is not a clef this writes. Use one of %."
                .format(
                    value.asCompileString,
                    this.clefs.collect { |each| each.asString }.join(", ")
                )
            ).throw
        };
        ^value.asSymbol
    }

    *new { |children, name, clef = \treble, shortName|
        ^super.new(children).initStaff(name, clef, shortName)
    }

    initStaff { |argName, argClef, argShortName|
        name = argName;
        clef = Staff.checkedClef(argClef);
        shortName = Staff.checkedShortName(argShortName);
        ^this
    }


    // The abbreviated name, printed after the first system.
    // Prose, like `name`, with no vocabulary to stay inside.
    //
    // Nil says nothing. Empty String is refused.
    //
    // >>> Staff.checkedShortName(nil)   -> nil
    *checkedShortName { |value|
        if (value.isNil) { ^nil };
        if (value.isKindOf(String).not) {
            Error(
                "Staff: shortName must be a String, got % (%).".format(value.asCompileString, value.class)
            ).throw
        };
        if (value.stripWhiteSpace.isEmpty) {
            Error(
                "Staff: empty shortName is written nil, not %.".format(value.asCompileString)
            ).throw
        };
        ^value
    }

    // Answers this, as `clef_` does.
    shortName_ { |value| shortName = Staff.checkedShortName(value); ^this }

    // Answers this, so it chains as the auto-generated setter did.
    clef_ { |value| clef = Staff.checkedClef(value); ^this }

    // Each independent timeline of the staff as one stream of leaves. Keyed by
    // voice position across bars. A bar with no `Voice` children is one
    // timeline at 0. Shared by tie and beam code.
    //
    // >>> Staff([Measure(Meter(1, 4), [MN(60, Duration(1, 4))]),
    //     Measure(Meter(1, 4), [MN(62, Duration(1, 4))])]).timelines.collect(_.size)
    // [ 2 ]
    timelines { ^Staff.timelinesOf(children) }

    // Same partition for bars not in a Staff.
    *timelinesOf { |bars|
        var streams = Dictionary.new;
        bars.do { |bar|
            if (bar.isKindOf(Measure)) {
                bar.voices.do { |voice, index|
                    streams[index] = (streams[index] ?? { List.new })
                        .addAll(voice.leaves)
                }
            }
        };
        ^streams.keys.asArray.sort.collect { |index| streams[index].asArray }
    }

    accept { |writer| ^writer.visitStaff(this) }

    // A summary in the shape `Voice` and `Tuplet` already use: what it holds
    // and what it is called. The tree itself is `postcs`.
    //
    // >>> Staff([Measure("2/4", "c4 d4")], "Violin").asString   -> Staff(1, Violin)
    // >>> Staff([]).asString                                    -> Staff(0)
    printOn { |stream|
        stream << "Staff(" << children.size;
        if (name.notNil) { stream << ", " << name };
        stream << ")"
    }

    // Default treble clef and absent short name elide.
    //
    // >>> Staff([], "Violin").asCompileString   -> Staff([], "Violin")
    storeArgs { ^[children.asArray, name, clef, shortName] }
}
