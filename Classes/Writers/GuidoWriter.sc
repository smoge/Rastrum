// GuidoWriter: GUIDO Music Notation (GMN), as a supported writer.
//
// Reached through `Rastrum.guido` and `Rastrum.writeGuido`. Narrower
// than LilyPond and MusicXML; unsupported or lossy facts are refused
// by name. GUIDO writes duration as a rational of a whole note. Leaf
// durations carry exact sound; `\tuplet<"3:2">` preserves the printed
// ratio.
//
//   {[ \staff<1> \clef<"treble"> \meter<"4/4"> c1*1/4 _*1/4 {c1*1/2,e1*1/2} | ]}
//
// Note [Refused rather than approximated]: a GMN guess can draw the
// wrong music, so unsupported spellings are errors.
//
// Note [A span is two positional tags]: ties, slurs, beams and
// hairpins use endpoint tags. Tempo ramps use range wrappers.
//
// Note [A marking is a prefix or a range]: dynamics prefix leaves.
// Articulations and text wrap them.
//
// Note [Containers become sequences]: Staff, Voice and Measure
// flatten into GUIDO sequences.
//
// Note [Metadata is dropped, not refused]: title, composer and staff
// names do not affect notes.
//
// Note [A quarter tone is a whole accidental plus an alteration]:
// whole accidentals stay in the pitch token. Quarter steps wrap pitch
// and length in `\alter`.
GuidoWriter : ScoreWriter {
    // Current timeline, meter and clef for the GUIDO sequence being written.
    var currentVoice, currentMeter, currentClef, staffNumber;

    // Open tie state, then open span ids.
    var tieOpen = false, tiedPitches;
    var slurOpen, beamOpen, hairpinOpen;
    // See Note [A glissando chain is one range].
    var glissandoOpen;
    // Mid-bar directions and ramp endpoints, keyed by the leaf they stand on.
    var pendingDirections, pendingRamps;
    var rampOpen;

    // GUIDO counts from a1 = A440, so its octave 1 is Rastrum's
    // octave 4.
    //
    // >>> GuidoWriter.pitchString(MusicPitch("c"))    -> c1
    // >>> GuidoWriter.pitchString(MusicPitch("eb,"))  -> e&0
    *octaveOffset { ^3 }

    *clefNames {
        ^(treble: "treble", bass: "bass", alto: "alto", tenor: "tenor",
            percussion: "perc")
    }

    *accidentalMarks {
        ^(doubleFlat: "&&", flat: "&", natural: "", sharp: "#",
            doubleSharp: "##")
    }

    // Note [A marking is a prefix or a range]. One row per supported
    // articulation. Caesura has no GMN spelling.
    //
    // Rows list tags outermost first. Portato uses tenuto plus staccato; the
    // probed `\portato` spelling draws nothing.
    *articulationTags {
        ^(staccato: ["\\staccato"],
            staccatissimo: ["\\staccato<type=\"heavy\">"],
            tenuto: ["\\tenuto"], accent: ["\\accent"],
            marcato: ["\\marcato"], fermata: ["\\fermata"],
            breath: ["\\breathMark"],
            portato: ["\\tenuto", "\\staccato"])
    }

    // Trimmed text for copy/paste into GUIDO tools.
    //
    // Open ranges are refused; GUIDO would otherwise close them ambiguously.
    write { |element|
        var out;
        currentVoice = nil;
        currentMeter = nil;
        currentClef = nil;
        staffNumber = nil;
        tieOpen = false;
        tiedPitches = nil;
        slurOpen = nil;
        beamOpen = nil;
        hairpinOpen = nil;
        rampOpen = nil;
        glissandoOpen = nil;
        out = super.write(element).stripWhiteSpace;
        this.prRequireTieClosed("the music ends with nothing after it");
        this.prRequireSlurClosed("the music ends with nothing after it");
        this.prRequireBeamClosed("the music ends with nothing after it");
        this.prRequireHairpinClosed("the music ends with nothing after it");
        this.prRequireRampClosed("the music ends with nothing after it");
        this.prRequireGlissandoClosed("the music ends with nothing after it");
        ^out
    }

    // Letter, accidental, octave. Keep spelling diatonic.
    *pitchString { |pitch, accidental|
        var mark;
        if (pitch.cents != 0) {
            Error("GuidoWriter: % carries % cents, and GUIDO engraves any "
                "residual as a quarter tone.".format(pitch, pitch.cents)).throw
        };
        mark = this.accidentalMarks[accidental ? pitch.accidental];
        if (mark.isNil) {
            Error("GuidoWriter: % is an accidental with no pinned GMN "
                "spelling.".format(pitch.accidental)).throw
        };
        ^"" ++ pitch.letter ++ mark ++ (pitch.octave - this.octaveOffset)
    }

    // The whole accidental a quarter tone leans on, or nil.
    // Note [A quarter tone is a whole accidental plus an alteration].
    *alterationBases {
        ^(quarterSharp: \natural, quarterFlat: \natural,
            threeQuarterSharp: \sharp, threeQuarterFlat: \flat)
    }

    // Pitch and length as one token, `\alter` wrapping both.
    //
    // >>> GuidoWriter.pitchToken(MusicPitch("c"), Duration(1, 4))
    // c1*1/4
    *pitchToken { |pitch, duration|
        var base = this.alterationBases[pitch.accidental];
        var written = this.pitchString(pitch, base)
            ++ this.durationString(duration);
        if (base.isNil) { ^written };
        ^"\\alter<%>(%)".format(
            if (pitch.alterationSteps > 0) { "0.5" } { "-0.5" }, written)
    }

    // A rational of a whole note. Dotted values shorter than a whole
    // keep dots; everything else is the exact fraction.
    //
    // Dots are written only on `1/d` heads. Multi-whole values stay fractions.
    //
    // >>> GuidoWriter.durationString(Duration(1, 4))    -> *1/4
    // >>> GuidoWriter.durationString(Duration(3, 8))    -> *1/4.
    // >>> GuidoWriter.durationString(Duration(1, 6))    -> *1/6
    // >>> GuidoWriter.durationString(Duration(5, 8))    -> *5/8
    // >>> GuidoWriter.durationString(Duration(6, 1))    -> *6/1
    *durationString { |dur|
        var pair = dur.notation;
        if (dur.numerator <= 0) {
            Error("GuidoWriter: % is not a length a leaf can have."
                .format(dur)).throw
        };
        if (pair.notNil and: { pair[0].numerator == 1 }) {
            ^"*1/" ++ pair[0].denominator ++ String.fill(pair[1], { $. })
        };
        ^"*" ++ dur.numerator ++ "/" ++ dur.denominator
    }

    // Grouped meters use GUIDO's additive spelling, `2+3/8`.
    // `Meter.grouped` already checked the groups.
    *meterString { |meter|
        var count = if (meter.isGrouped) {
            meter.groups.join("+")
        } {
            meter.count
        };
        ^"\\meter<\"" ++ count ++ "/" ++ meter.unit ++ "\"> "
    }

    *clefString { |clef|
        var name = this.clefNames[clef];
        if (name.isNil) {
            Error("GuidoWriter: % is a clef with no pinned GMN spelling."
                .format(clef)).throw
        };
        ^"\\clef<\"" ++ name ++ "\"> "
    }

    // Two of either is the last written, by Note [Two marks at one moment] in
    // ScoreWriter.sc.
    //
    // >>> GuidoWriter.sforzandoString(MN("c4:sfz")).stripWhiteSpace
    // \intens<"sfz">
    // >>> GuidoWriter.sforzandoString(MN("c4:sffz:pp")).stripWhiteSpace
    // \intens<"sffz/pp">
    *sforzandoString { |leaf|
        var word = Marking.sforzandoSpelling(this.sforzandoOf(leaf).value);
        var dynamic = this.dynamicOf(leaf);
        dynamic !? { word = word ++ "/" ++ this.dynamicTag(dynamic.value) };
        ^"\\intens<\"" ++ word ++ "\"> "
    }

    // One row per dynamic, read rather than interpolated. Unpinned GMN dynamics
    // can parse and draw nothing.
    // See Note [A dynamic is pinned, not interpolated] in LilyWriter.sc.
    *dynamicTags {
        ^(ppppp: "ppppp", pppp: "pppp", ppp: "ppp", pp: "pp", p: "p",
            mp: "mp", mf: "mf", f: "f", ff: "ff", fff: "fff", ffff: "ffff",
            fffff: "fffff")
    }

    // >>> GuidoWriter.dynamicTag(\mf)   -> mf
    *dynamicTag { |value|
        var tag = this.dynamicTags[value];
        if (tag.isNil) {
            Error("GuidoWriter: % is a dynamic with no pinned GMN spelling."
                .format(value)).throw
        };
        ^tag
    }

    // >>> GuidoWriter.dynamicString(Marking.dynamic(\mf))   -> \intens<"mf">
    *dynamicString { |marking|
        ^"\\intens<\"" ++ this.dynamicTag(marking.value) ++ "\"> "
    }

    // Pinned GMN technical marks. Unsupported technicals are refused by value.
    *technicalTags { ^(harmonic: ["\\harmonic"]) }

    // Nil lets `prRefuseDecoration` own the unsupported-mark error.
    *technicalTagsFor { |marking| ^this.technicalTags[marking.value] }

    *articulationTagsFor { |marking|
        var tag = this.articulationTags[marking.value];
        if (tag.isNil) {
            Error("GuidoWriter: % is an articulation with no pinned GMN "
                "spelling."
                .format(marking.value)).throw
        };
        ^tag
    }

    // Placement chooses the tag. See Note [A marking is a prefix or a range].
    //
    // >>> GuidoWriter.textTag(Marking.text("sul pont."))          -> \mark<"sul pont.">
    // >>> GuidoWriter.textTag(Marking.text("sul pont.", \below))  -> \text<"sul pont.">
    *textTag { |marking|
        this.checkedWords(marking.value);
        ^if (marking.placement == \above) { "\\mark<\"" } { "\\text<\"" }
            ++ marking.value ++ "\">"
    }

    // Prose bound for a GMN tag string. See Note [Refused rather than approximated].
    *checkedWords { |words|
        var quote = RastrumChar.doubleQuote, backslash = RastrumChar.backslash;
        if (words.includes(quote) or: { words.includes(backslash) }) {
            Error("GuidoWriter: text % carries a quote or backslash. "
                "GMN escaping is not pinned here."
                .format(words.asCompileString)).throw
        };
        if (words.any { |char| [Char.nl, Char.ret, Char.tab].includes(char) }) {
            Error("GuidoWriter: text % carries a line break or tab. Write it as "
                "one line.".format(words.asCompileString)).throw
        };
        ^words
    }

    // Mid-bar directions, keyed by the leaf each stands before.
    prDirectionsByLeaf { |measure|
        var pending = IdentityDictionary.new;
        var offsets, local;
        var mid = measure.directions.reject { |each|
            each.atBarStart or: { each.isTempoRamp } };
        if (mid.isEmpty) { ^pending };
        offsets = ScorePrepare.leafOffsetsIn(measure);
        local = { |leaf| offsets[leaf] - measure.metricOffset };
        mid.do { |direction|
            // Require every voice, then use the first voice's leaf.
            var voices = if (measure.hasVoices) { measure.voices } { [measure] };
            var missing = voices.detectIndex { |voice|
                voice.leaves.any { |leaf|
                    local.(leaf) == direction.offset }.not };
            var leaf;
            if (missing.notNil) {
                Error("GuidoWriter: \"%\" is written % into this bar, and voice "
                    "% has no leaf there. Directions must sit at a shared leaf "
                    "boundary.".format(
                        direction.text, direction.offset, missing + 1)).throw
            };
            leaf = voices.first.leaves.detect { |each|
                local.(each) == direction.offset };
            pending[leaf] = (pending[leaf] ? []) ++ [direction]
        };
        ^pending
    }

    // Tempo range wrappers, keyed by the leaf they enclose.
    prRampsByLeaf { |measure|
        var pending = IdentityDictionary.new;
        var ramps = measure.directions.select { |each| each.isTempoRamp };
        var offsets, local;

        if (ramps.isEmpty) { ^pending };
        offsets = ScorePrepare.leafOffsetsIn(measure);
        local = { |leaf| offsets[leaf] - measure.metricOffset };
        Validator.inSpanOrder(ramps).do { |endpoint|
            var missing = measure.voices.detectIndex { |voice|
                voice.leaves.any { |leaf| local.(leaf) == endpoint.offset }.not
            };
            var leaf, here;
            if (missing.notNil) {
                Error("GuidoWriter: a tempo ramp % is written % into this bar, "
                    "and voice % has no leaf there. Ramps must sit at a shared "
                    "leaf boundary.".format(
                        endpoint.edge, endpoint.offset, missing + 1)).throw
            };
            leaf = measure.voices.first.leaves.detect { |each|
                local.(each) == endpoint.offset
            };
            here = pending[leaf] ? [];
            if (here.any { |each| each.edge != endpoint.edge }) {
                Error("GuidoWriter: a tempo ramp closes and another opens on "
                    "one leaf. GUIDO range tags would overlap there.").throw
            };
            this.prTrackRamp(endpoint);
            pending[leaf] = here ++ [endpoint]
        };
        ^pending
    }

    prTrackRamp { |endpoint|
        if (endpoint.isRampStart) {
            if (rampOpen.notNil) {
                Error("GuidoWriter: a tempo ramp is already open when id % "
                    "starts. Close the first ramp before opening another."
                    .format(endpoint.id)).throw
            };
            GuidoWriter.tempoRampTag(endpoint);
            rampOpen = endpoint.id;
            ^this
        };
        if (rampOpen.isNil) {
            Error("GuidoWriter: a tempo ramp stop with id % closes nothing."
                .format(endpoint.id)).throw
        };
        if (endpoint.id != rampOpen) {
            Error("GuidoWriter: a tempo ramp stop with id % closes where id % "
                "is open.".format(endpoint.id, rampOpen)).throw
        };
        rampOpen = nil;
        ^this
    }

    // Note [A glissando chain is one range]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // GUIDO's range form matches a chain. A leaf carrying both ends
    // is inside the open range, so it writes no boundary. GUIDO pairs
    // chord noteheads by their position in the code, the same pairing
    // the other two backends use. Ids are checked even on a chain's
    // interior leaf.

    prGlissandoBefore { |leaf|
        var glissandi = leaf.spanners.select { |each| each.isGlissando };
        var stops = glissandi.select { |each| each.isStop };
        var starts = glissandi.select { |each| each.isStart };
        // Stops first: a chain closes one line on the attack opening the next.
        stops.do { |endpoint|
            if (glissandoOpen.isNil) {
                Error("GuidoWriter: glissando id % closes nothing.".format(
                    endpoint.id)).throw
            };
            if (endpoint.id != glissandoOpen) {
                Error("GuidoWriter: glissando id % closes where id % is open."
                    .format(endpoint.id, glissandoOpen)).throw
            };
            glissandoOpen = nil;
        };
        starts.do { |endpoint|
            if (glissandoOpen.notNil) {
                Error("GuidoWriter: glissando id % opens while id % is still "
                    "open. GUIDO draws one range at a time.".format(
                        endpoint.id, glissandoOpen)).throw
            };
            glissandoOpen = endpoint.id;
        };
        // Interior attacks are already inside the range.
        if (starts.notEmpty and: { stops.isEmpty }) {
            stream << "\\glissando("
        };
        ^this
    }

    prGlissandoAfter { |leaf|
        var glissandi = leaf.spanners.select { |each| each.isGlissando };
        // Interior attacks stay inside the range. Pairing was checked
        // before the leaf.
        if (glissandi.any { |each| each.isStart }) { ^this };
        if (glissandi.any { |each| each.isStop }) { stream << ")" };
        ^this
    }

    prRequireGlissandoClosed { |ending|
        if (glissandoOpen.notNil) {
            Error("GuidoWriter: a glissando with id % is still open, but %. A "
                "range tag needs both ends.".format(glissandoOpen, ending)).throw
        };
        ^this
    }

    prRequireRampClosed { |ending|
        if (rampOpen.notNil) {
            Error("GuidoWriter: a tempo ramp with id % is still open, but %. A "
                "range tag needs both ends.".format(rampOpen, ending)).throw
        };
        ^this
    }

    prWriteDirectionsAt { |leaf|
        pendingDirections !? { |found|
            found[leaf] !? { |here|
                here.do { |each| stream << GuidoWriter.directionString(each) }
            }
        };
        ^this
    }

    prRampBefore { |leaf|
        pendingRamps !? { |found|
            found[leaf] !? { |here|
                here.do { |endpoint|
                    if (endpoint.isRampStart) {
                        stream << GuidoWriter.tempoRampTag(endpoint) << "("
                    }
                }
            }
        };
        ^this
    }

    prRampAfter { |leaf|
        pendingRamps !? { |found|
            found[leaf] !? { |here|
                here.do { |endpoint|
                    if (endpoint.isRampStop) { stream << ")" }
                }
            }
        };
        ^this
    }

    // Note [A span is two positional tags]. Point directions are written once
    // for the staff.
    //
    // Keep Rastrum's attachment order, though GUIDO draws either order alike.
    //
    // >>> GuidoWriter.directionString(
    //     Direction.tempo("Allegro", beat: "4", bpm: 120)).asCompileString
    // "\\tempo<\"Allegro [1/4]=120\"> "
    // >>> GuidoWriter.directionString(Direction.rehearsalMark("A")).asCompileString
    // "\\label<\"A\"> "
    // >>> GuidoWriter.directionString(Direction.text("solo")).asCompileString
    // "\\mark<\"solo\"> "
    *writesTempoRamps { ^true }

    *directionString { |direction|
        var words;
        this.prRequireWritableDirection(direction);
        if (direction.isTempoRamp) {
            Error("GuidoWriter: tempo ramp endpoints are ranges, not point "
                "directions.")
                .throw
        };
        if (direction.isTempo.not) {
            words = this.checkedWords(direction.text);
            ^if (direction.isRehearsalMark) { "\\label<\"" } { "\\mark<\"" }
                ++ words ++ "\"> "
        };
        words = direction.text !? { |text| this.checkedWords(text) } ?? { "" };
        if (direction.hasMetronome) {
            // GUIDO engraves `[1/4.]` as `[1/4]`, dropping the dot,
            // so a dotted beat would print a tempo the score doesn't
            // say.
            if ((direction.unit.notation !? { |each| each[1] } ? 0) > 0) {
                Error("GuidoWriter: a metronome beat of % is dotted, and GUIDO "
                    "draws no dot in a tempo string.".format(direction.unit)).throw
            };
            if (words.notEmpty) { words = words ++ " " };
            words = words ++ "[%]=%".format(
                this.durationString(direction.unit).drop(1), direction.perMinute)
        };
        ^"\\tempo<\"" ++ words ++ "\"> "
    }

    // GUIDO has two named tempo range tags. The words choose which one.
    *tempoRampTag { |direction|
        var words, slows, speeds;
        if (direction.isRampStart.not) {
            Error("GuidoWriter: a tempo ramp stop carries no GUIDO tag.")
                .throw
        };
        if (direction.hasText.not) {
            Error("GuidoWriter: a tempo ramp start needs words containing "
                "\"rit.\"/\"ritardando\" or \"accel.\"/\"accelerando\" so GUIDO "
                "can choose its range tag.").throw
        };
        words = this.checkedWords(direction.text);
        slows = words.containsi("rit.") or: { words.containsi("ritardando") };
        speeds = words.containsi("accel.") or: { words.containsi("accelerando") };
        if (slows == speeds) {
            Error("GuidoWriter: tempo ramp text % does not choose one GUIDO tag. "
                "Use rit., ritardando, accel. or accelerando in the start text."
                .format(words.asCompileString)).throw
        };
        ^(if (slows) { "\\ritardando<\"" } { "\\accelerando<\"" })
            ++ words ++ "\">"
    }

    // Before the host leaf, outside its marking wrappers.
    //
    // Acciaccatura is refused: no GMN spelling draws the slash. Grace
    // leaves may carry only pitch and display length.
    *checkedGrace { |grace|
        // Check the leaf kind before fields only notes and chords answer.
        if (grace.isKindOf(MusicNote).not and: { grace.isKindOf(Chord).not }) {
            Error("GuidoWriter: a grace group holds %.".format(
                if (grace.isKindOf(MusicRest)) {
                    "a rest, and an ornament is a sound"
                } {
                    "a %, and a group holds notes and chords".format(grace.class)
                })).throw
        };
        if (grace.markings.notEmpty) {
            Error("GuidoWriter: a grace leaf carries a marking, which GUIDO "
                "writing does not support.").throw
        };
        if (grace.hasSpanners) {
            Error("GuidoWriter: a grace leaf carries a spanner endpoint, which "
                "GUIDO writing does not support.").throw
        };
        if (grace.hasGraces) {
            Error("GuidoWriter: a grace leaf carries a nested grace group.").throw
        };
        if (grace.dur.isNotatable.not) {
            Error("GuidoWriter: a grace leaf lasts %, a display value no note "
                "head spells.".format(grace.dur)).throw
        };
        if (grace.isKindOf(Chord)) {
            if (grace.tiesToNext.asArray.any { |each| each == true }) {
                Error("GuidoWriter: a grace leaf ties onward.").throw
            }
        } {
            if (grace.tiesToNext == true) {
                Error("GuidoWriter: a grace leaf ties onward.").throw
            }
        };
        ^grace
    }

    *graceString { |leaf, span|
        if (leaf.graceStyle != \grace) {
            Error("GuidoWriter: % carries an %, and no GMN spelling draws the "
                "slash that distinguishes it from a plain grace.".format(leaf.class, leaf.graceStyle)).throw
        };
        ^"\\grace(" ++ leaf.graces.collect { |each|
            this.checkedGrace(each);
            if (each.isKindOf(Chord)) {
                "{" ++ each.pitches.collect { |pitch|
                    this.pitchToken(pitch, each.dur) }.join(",") ++ "}"
            } {
                this.pitchToken(each.pitch, each.dur)
            }
        }.join(" ") ++ ") "
    }

    // See Note [Refused rather than approximated]. Leaf decoration
    // refusals.
    prRefuseDecoration { |leaf|
        // Text spanners lack a GMN spelling that draws a span.
        leaf.spanners.do { |endpoint|
            if ([\slur, \beam, \hairpin, \glissando].includes(endpoint.kind).not) {
                Error("GuidoWriter: % carries a % spanner, which has no pinned "
                    "GMN spelling here.".format(
                        leaf.class, endpoint.kind)).throw
            }
        };
        // Per value rather than per kind: `\harmonic` is pinned, the rest are
        // unsupported.
        leaf.technicals.do { |marking|
            if (GuidoWriter.technicalTagsFor(marking).isNil) {
                Error("GuidoWriter: % is a technical mark with no pinned GMN "
                    "spelling.".format(marking.value)).throw
            }
        };
        ^this
    }

    // Note [A span is two positional tags]. One slur at a time: GMN
    // endpoints carry no id. A leaf carrying both ends is ambiguous
    // in GMN, same id or not.
    prSlurBefore { |leaf|
        var slurs = leaf.spanners.select { |each| each.kind == \slur };
        var starts = slurs.select { |each| each.isStart };
        starts.do { |start|
            slurs.reject { |each| each.isStart }.do { |stop|
                if (stop.id == start.id) {
                    Error("GuidoWriter: % opens and closes slur id % on one "
                        "attack.".format(leaf.class, start.id)).throw
                };
                Error("GuidoWriter: % ends slur id % and begins id % on one "
                    "attack, which GUIDO cannot disambiguate.".format(leaf.class, stop.id, start.id)).throw
            }
        };
        starts.do { |endpoint|
            if (slurOpen.notNil) {
                Error("GuidoWriter: slur id % opens while id % is still open. "
                    "GUIDO slur endpoints carry no id.".format(endpoint.id, slurOpen)).throw
            };
            stream << "\\slurBegin ";
            slurOpen = endpoint.id;
        };
        ^this
    }

    prSlurAfter { |leaf|
        leaf.spanners.select { |each|
            each.kind == \slur and: { each.isStart.not } }.do { |endpoint|
            if (slurOpen.isNil) {
                Error("GuidoWriter: slur id % closes nothing.".format(endpoint.id)).throw
            };
            if (endpoint.id != slurOpen) {
                Error("GuidoWriter: slur id % closes where id % is open. GUIDO "
                    "slur endpoints carry no id.".format(endpoint.id, slurOpen)).throw
            };
            stream << "\\slurEnd ";
            slurOpen = nil;
        };
        ^this
    }

    // Note [A span is two positional tags]. One beam at a time, ids
    // still match. Refuse unbeamable leaves under a beam; GUIDO would
    // draw nothing.
    prBeamBefore { |leaf|
        var beams = leaf.spanners.select { |each| each.kind == \beam };
        // A note cannot end and begin one beam group.
        if (beams.any { |each| each.isStart }
            and: { beams.any { |each| each.isStart.not } }) {
            Error("GuidoWriter: % both ends and begins a beam.".format(
                leaf.class)).throw
        };
        beams.select { |each| each.isStart }.do { |endpoint|
            if (beamOpen.notNil) {
                Error("GuidoWriter: beam id % opens while id % is still open."
                    .format(
                        endpoint.id, beamOpen)).throw
            };
            stream << "\\beamBegin ";
            beamOpen = endpoint.id;
        };
        // Check every leaf under the beam.
        if (beamOpen.notNil and: { AutoBeam.isBeamable(leaf).not }) {
            Error("GuidoWriter: % under a beam has no flag to beam."
                .format(leaf.class)).throw
        };
        ^this
    }

    prBeamAfter { |leaf|
        leaf.spanners.select { |each|
            each.kind == \beam and: { each.isStart.not } }.do { |endpoint|
            if (beamOpen.isNil) {
                Error("GuidoWriter: beam id % closes nothing."
                    .format(endpoint.id)).throw
            };
            if (endpoint.id != beamOpen) {
                Error("GuidoWriter: beam id % closes where id % is open."
                    .format(endpoint.id, beamOpen)).throw
            };
            stream << "\\beamEnd ";
            beamOpen = nil;
        };
        ^this
    }

    // Note [A span is two positional tags]. Direction chooses the
    // tag. the stop reads it from the open start. One hairpin at a
    // time. A leaf cannot close one and open another here.
    *hairpinTags {
        ^(crescendo: ["\\crescBegin ", "\\crescEnd "],
            diminuendo: ["\\decrescBegin ", "\\decrescEnd "])
    }

    prHairpinBefore { |leaf|
        var hairpins = leaf.spanners.select { |each| each.kind == \hairpin };
        if (hairpins.any { |each| each.isStart }
            and: { hairpins.any { |each| each.isStart.not } }) {
            Error("GuidoWriter: % ends and begins a hairpin on one attack."
                .format(leaf.class)).throw
        };
        hairpins.select { |each| each.isStart }.do { |endpoint|
            if (hairpinOpen.notNil) {
                Error("GuidoWriter: hairpin id % opens while id % is still "
                    "open.".format(endpoint.id, hairpinOpen[0])).throw
            };
            stream << GuidoWriter.hairpinTags[endpoint.direction][0];
            hairpinOpen = [endpoint.id, endpoint.direction];
        };
        ^this
    }

    prHairpinAfter { |leaf|
        leaf.spanners.select { |each|
            each.kind == \hairpin and: { each.isStart.not } }.do { |endpoint|
            if (hairpinOpen.isNil) {
                Error("GuidoWriter: hairpin id % closes nothing."
                    .format(endpoint.id)).throw
            };
            if (endpoint.id != hairpinOpen[0]) {
                Error("GuidoWriter: hairpin id % closes where id % is open."
                    .format(endpoint.id, hairpinOpen[0])).throw
            };
            // The stop tag comes from what the start opened.
            stream << GuidoWriter.hairpinTags[hairpinOpen[1]][1];
            hairpinOpen = nil;
        };
        ^this
    }

    prRequireHairpinClosed { |ending|
        if (hairpinOpen.notNil) {
            Error("GuidoWriter: hairpin id % is still open, but %. A hairpin "
                "needs a following note.".format(hairpinOpen[0], ending)).throw
        };
        ^this
    }

    prRequireBeamClosed { |ending|
        if (beamOpen.notNil) {
            Error("GuidoWriter: beam id % is still open, but %. A beam "
                "needs a following note.".format(beamOpen, ending)).throw
        };
        ^this
    }

    prRequireSlurClosed { |ending|
        if (slurOpen.notNil) {
            Error("GuidoWriter: slur id % is still open, but %. A slur may "
                "cross a barline into the same voice of the next bar, but it "
                "needs a following note.".format(slurOpen, ending)).throw
        };
        ^this
    }

    // Note [A span is two positional tags]. One pair spans a whole run.
    //
    // Refuse ties between different pitches and partial chord ties.
    prTieBefore { |leaf, pitches, onward|
        if (tieOpen) {
            if (pitches.isEmpty) {
                Error("GuidoWriter: a tie arrives at a %, not a note."
                    .format(leaf.class)).throw
            };
            if (GuidoWriter.samePitches(pitches, tiedPitches).not) {
                Error("GuidoWriter: a tie leaves % and arrives at %. GUIDO "
                    "draws no tie between different pitches.".format(
                        tiedPitches.join(" "), pitches.join(" "))).throw
            }
        } {
            if (onward) { stream << "\\tieBegin "; tieOpen = true }
        };
        tiedPitches = if (tieOpen) { pitches } { nil };
        ^this
    }

    // And after it, on the last leaf of the run. A barline and a
    // tuplet bracket fall between the two tags without either having
    // to know.
    prTieAfter { |onward|
        if (tieOpen and: { onward.not }) {
            stream << "\\tieEnd ";
            tieOpen = false;
            tiedPitches = nil;
        };
        ^this
    }

    prRequireTieClosed { |ending|
        if (tieOpen) {
            Error("GuidoWriter: % ties onward, but %. A tie may cross a barline "
                "into the same voice of the next bar, but it must reach some "
                "note.".format(tiedPitches.join(" "), ending)).throw
        };
        ^this
    }

    // Spelling and octave, not sounding height.
    *samePitches { |a, b|
        ^(a.size == b.size) and: { a.every { |pitch, i| pitch == b[i] } }
    }

    // A chord ties as a whole or not at all here.
    prChordTiesOnward { |chord|
        if (chord.tiesAnything.not) { ^false };
        if (chord.tiesAll) { ^true };
        Error("GuidoWriter: % ties some of its pitches and not others, and the "
            "candidate GMN for that draws nothing.".format(chord.class)).throw
    }

    // Sounding time, not written: inside a tuplet the two differ, and
    // the sounding one is what GUIDO reads. Outside one they are the
    // same value.
    prLeafDuration { |leaf| ^leaf.prolatedDuration }

    // Note [A marking is a prefix or a range]. The body writes no
    // trailing space; wrappers close against it.
    prWriteMarked { |leaf, body|
        var wrappers = this.prWrappersFor(leaf);
        // A sforzando plus dynamic joins one `\intens` tag.
        if (leaf.sforzandos.notEmpty) {
            stream << GuidoWriter.sforzandoString(leaf)
        } {
            GuidoWriter.dynamicOf(leaf) !? { |marking|
                stream << GuidoWriter.dynamicString(marking) };
        };
        wrappers.do { |tag| stream << tag << "(" };
        body.value;
        wrappers.do { stream << ")" };
        this.prRampAfter(leaf);
        stream << " ";
    }

    // Outermost first: text outside, articulations against the note head.
    prWrappersFor { |leaf|
        ^leaf.texts.collect { |marking| GuidoWriter.textTag(marking) }
            ++ leaf.articulations.inject([], { |all, marking|
                all ++ GuidoWriter.articulationTagsFor(marking) })
            ++ leaf.technicals.inject([], { |all, marking|
                all ++ (GuidoWriter.technicalTagsFor(marking) ? []) })
    }

    visitNote { |note|
        this.prRefuseDecoration(note);
        this.prWriteDirectionsAt(note);
        this.prGlissandoBefore(note);
        this.prRampBefore(note);
        this.prSlurBefore(note);
        this.prHairpinBefore(note);
        this.prBeamBefore(note);
        if (note.hasGraces) { stream << GuidoWriter.graceString(note) };
        this.prTieBefore(note, [note.pitch], note.tiesToNext);
        this.prWriteMarked(note, {
            stream << GuidoWriter.pitchToken(note.pitch,
                this.prLeafDuration(note))
        });
        this.prTieAfter(note.tiesToNext);
        this.prBeamAfter(note);
        this.prHairpinAfter(note);
        this.prSlurAfter(note);
        this.prGlissandoAfter(note);
    }

    // `_` is GUIDO's rest and takes a duration.
    visitRest { |rest|
        this.prRefuseDecoration(rest);
        this.prWriteDirectionsAt(rest);
        this.prGlissandoBefore(rest);
        this.prRampBefore(rest);
        this.prSlurBefore(rest);
        this.prHairpinBefore(rest);
        this.prBeamBefore(rest);
        if (rest.hasGraces) { stream << GuidoWriter.graceString(rest) };
        this.prTieBefore(rest, [], false);
        this.prWriteMarked(rest, {
            stream << "_" << GuidoWriter.durationString(this.prLeafDuration(rest))
        });
        this.prBeamAfter(rest);
        this.prHairpinAfter(rest);
        this.prSlurAfter(rest);
        this.prGlissandoAfter(rest);
    }

    // Braces, comma separated. One duration for the chord.
    visitChord { |chord|
        var span = this.prLeafDuration(chord);
        var onward;
        this.prRefuseDecoration(chord);
        this.prWriteDirectionsAt(chord);
        this.prGlissandoBefore(chord);
        this.prRampBefore(chord);
        this.prSlurBefore(chord);
        this.prHairpinBefore(chord);
        this.prBeamBefore(chord);
        if (chord.hasGraces) { stream << GuidoWriter.graceString(chord) };
        onward = this.prChordTiesOnward(chord);
        this.prTieBefore(chord, chord.pitches, onward);
        this.prWriteMarked(chord, {
            stream << "{"
                << chord.pitches.collect { |pitch|
                    GuidoWriter.pitchToken(pitch, span) }.join(",")
                << "}"
        });
        this.prTieAfter(onward);
        this.prBeamAfter(chord);
        this.prHairpinAfter(chord);
        this.prSlurAfter(chord);
        this.prGlissandoAfter(chord);
    }

    // Children carry exact sounding rationals. The tag carries the
    // printed bracket, using authored counts rather than reduced
    // ratio. A trivial bracket scales nothing and prints nothing.
    visitTuplet { |tuplet|
        var outer, inside;
        if (tuplet.isTrivial) { ^this.writeChildren(tuplet) };
        // Isolate children so the closing paren can sit against the last leaf.
        outer = stream;
        stream = CollStream.on(String.new);
        this.writeChildren(tuplet);
        inside = stream.collection.stripWhiteSpace;
        stream = outer;
        stream << "\\tuplet<\"" << tuplet.actualNotes << ":"
               << tuplet.normalNotes << "\">(" << inside << ") ";
    }

    visitVoice { |voice| this.writeChildren(voice) }

    visitMeasure { |measure|
        // A bar is timelines or leaves, never both.
        if (measure.mixesVoicesWithElements) {
            Error("GuidoWriter: a bar of % holds a voice beside a leaf. A bar "
                "must hold either one timeline or several voices."
                .format(measure.meter)).throw
        };
        measure.clef !? { |clef|
            if (clef != currentClef) {
                currentClef = clef;
                stream << GuidoWriter.clefString(clef)
            }
        };
        if (measure.meter.notNil and: { measure.meter != currentMeter }) {
            currentMeter = measure.meter;
            stream << GuidoWriter.meterString(measure.meter)
        };
        // Once for the staff.
        pendingDirections = if ((currentVoice ? 0) == 0) {
            this.prDirectionsByLeaf(measure)
        } {
            IdentityDictionary.new
        };
        pendingRamps = if ((currentVoice ? 0) == 0) {
            this.prRampsByLeaf(measure)
        } {
            IdentityDictionary.new
        };
        if ((currentVoice ? 0) == 0) {
            measure.directions.select { |each|
                each.atBarStart and: { each.isTempoRamp.not } }.do { |each|
                stream << GuidoWriter.directionString(each)
            }
        };
        this.prWriteTimeline(measure);
        pendingDirections = nil;
        pendingRamps = nil;
        stream << "| ";
    }

    // The one bar this sequence is reading. Voice-shape drift is refused.
    prWriteTimeline { |measure|
        var voice = currentVoice ? 0;
        if (measure.hasVoices.not) {
            if (voice > 0) {
                Error("GuidoWriter: a bar of % holds no voices while another "
                    "bar of the same staff holds several.".format(
                        measure.meter)).throw
            };
            ^this.writeChildren(measure)
        };
        if (voice >= measure.voices.size) {
            Error("GuidoWriter: a bar of % holds % voices where the staff is "
                "written as %.".format(measure.meter, measure.voices.size,
                    this.prVoiceCount(measure.parent ?? { measure }))).throw
        };
        ^measure.voices[voice].accept(this)
    }

    // One sequence per timeline, by Note [Containers become sequences].
    visitStaff { |staff|
        var many = this.prVoiceCount(staff);
        var wrap = (many > 1) and: { staffNumber.isNil };
        if (wrap) { stream << "{" };
        many.do { |voice|
            if (voice > 0) { stream << ", " };
            this.prWriteSequence(staff, voice)
        };
        if (wrap) { stream << "}" };
    }

    // Widest bar says how many sequences the staff is.
    prVoiceCount { |staff|
        ^staff.children.collect { |measure|
            if (measure.isKindOf(Measure)) { measure.voices.size } { 1 }
        }.maxItem ? 1
    }

    prWriteSequence { |staff, voice|
        currentVoice = voice;
        currentMeter = nil;
        currentClef = staff.clef;
        tieOpen = false;
        tiedPitches = nil;
        slurOpen = nil;
        beamOpen = nil;
        hairpinOpen = nil;
        rampOpen = nil;
        glissandoOpen = nil;
        stream << "[";
        staffNumber !? { |n| stream << "\\staff<" << n << "> " };
        staff.clef !? { |clef| stream << GuidoWriter.clefString(clef) };
        this.writeChildren(staff);
        // A tie is a range inside one sequence; close checks happen before reset.
        this.prRequireTieClosed("the staff ends");
        this.prRequireSlurClosed("the staff ends");
        this.prRequireBeamClosed("the staff ends");
        this.prRequireHairpinClosed("the staff ends");
        this.prRequireRampClosed("the staff ends");
        this.prRequireGlissandoClosed("the staff ends");
        stream << "]";
        currentVoice = nil;
        ^this
    }

    // A score is its sequences between braces.
    visitScore { |score|
        stream << "{";
        score.children.do { |staff, index|
            if (index > 0) { stream << ", " };
            staffNumber = index + 1;
            staff.accept(this);
        };
        staffNumber = nil;
        stream << "}";
    }
}
