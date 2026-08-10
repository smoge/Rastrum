// Note [What a beam may hold]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Two rules are enforced here and only the first of them is notation.
//
// A beam joins note heads, so what it may hold is narrow: notes and chords
// shorter than a quarter, and nothing else. A rest breaks one because there is
// no head to draw the bar through, and a quarter because it has no flag for a
// beam to be. That holds wherever the music is printed.
//
// Every note under one beam carries the same number of flags. Where they differ
// the group needs partial beams (the short hook stopping in mid-air over a lone
// sixteenth), and a hook is a third state per level beside begin and end,
// decided by which neighbor the note leans toward. MusicXML has to be told that
// explicitly, so guessing would draw the wrong thing rather than nothing.
//
// A beam crossing a barline is *not* wrong notation, and refusing it is this
// quark's limitation rather than a rule. All three of these were checked rather
// than assumed. LilyPond engraves a manual beam across a barline with no tweak
// and no warning. A document whose beam begins in one measure and ends in the
// next validates against the MusicXML partwise DTD, which scopes a beam to the
// note it sits on and not to the measure. `musicxml2ly` reads that document and
// writes the bracket back out spanning the barline.
//
// What stands in the way is here rather than in the backends. `prCheckBeams`
// walks bar by bar where `prCheckStaffTies` walks each voice across the whole
// staff, so beams could follow ties. The open question is preparation: a bar
// being re-cut may split a beamed leaf, and what a beam should do then is a
// decision nobody has made.


// Validator: structural checks on a score tree, before anything writes it.
//
// These are the mistakes the model will hold happily and the writers catch
// unevenly. A dangling tie is the clearest case: MusicXML has to know what
// follows a tied note in order to spell the arriving half, so it throws.
// LilyPond marks the note it leaves and cannot tell, so it emits a `~` with
// nothing after it and produces a file LilyPond itself rejects. Checking here
// covers every writer at once. A rule enforced inside one backend is a rule the
// other backends do not have.
//
// It reads, and answers the tree it was given. No rewriting: repair belongs to
// ScorePrepare, and a validator that quietly fixed things would hide exactly
// the problems it exists to report. No output syntax either. Nothing here
// knows that LilyPond or MusicXML exist.
//
// Some rules here are also refused at construction. That is not redundancy: a
// tree can arrive by a route no constructor saw: `ScoreJSONReader`, a
// hand-built node, a field set after the fact. A rule a writer depends on
// has to hold whichever way the tree was made.
//
// Wired into the facade, not into the writers, which is also why a few of these
// rules are stated a second time inside a writer. See
// Note [A writer refuses what it depends on] in ScoreWriter.sc.
Validator {

    // Returns the element unchanged, or throws. `requirePrepared` adds the
    // checks that only hold after ScorePrepare has run: every leaf writable as
    // a single note head. Off, the tree is checked for the mistakes that are
    // wrong at any stage.
    //
    // A half note is half a 4/4 bar, and a bar that does not add up is the one
    // thing no writer can be handed. What each refusal *says* is a test's
    // business, not an example's. See Tests/TestValidator.sc.
    //
    // >>> Validator.validate(RhythmTree.measure(Meter(4, 4), [1, 1, 1, 1]))
    //     .isKindOf(Measure)   -> true
    // >>> try { Validator.validate(Measure(Meter(4, 4),
    //     [MusicNote(60, Duration(1, 2))])) } { \refused }   -> refused
    *validate { |element, requirePrepared = false|
        this.prCheckStructure(element, requirePrepared);
        this.prCheckTies(element);
        this.prCheckBeams(element);
        this.prCheckSpanners(element);
        ^element
    }

    // A bar is reached before anything it holds, so the rests it declares to be
    // a whole bar of silence are known by the time those leaves come round.
    *prCheckStructure { |element, requirePrepared|
        var barRests = IdentitySet.new;
        element.traverse { |node|
            case
                { node.isKindOf(Tuplet)  } { this.prCheckTuplet(node) }
                { node.isKindOf(Voice)   } { this.prCheckVoicePlacement(node) }
                { node.isKindOf(Measure) } {
                    this.prCheckMeasure(node);
                    node.wholeBarRests.do { |rest| barRests.add(rest) };
                }
                { node.isKindOf(Chord) } { this.prCheckChord(node) }
                { true } { };
            if (node.isKindOf(ScoreContainer)) {
                this.prCheckParents(node);
                this.prCheckContents(node);
            };
            if (node.isLeaf) { this.prCheckGraces(node) };
            if (requirePrepared and: { node.isLeaf }) {
                this.prCheckNotatable(node, barRests.includes(node))
            };
        };
        ^this
    }

    *prCheckTuplet { |tuplet|
        var ratio = tuplet.ratio;
        if (ratio.numerator <= 0 or: { ratio.denominator <= 0 }) {
            Error("Validator: % has a multiplier that is not positive. A tuplet "
                "scales time, so its ratio must be greater than zero.".format(
                    tuplet)).throw
        };
        // A count is a number of notes, so it is whole and positive on both
        // sides. Checked before the pair is compared: 0:0 and 1.5:1.5 agree
        // with any multiplier under cross-multiplication, and neither is a
        // bracket. The first reaches a writer as `\tuplet 0/0`.
        if (tuplet.actualNotes.isKindOf(Integer).not
            or: { tuplet.actualNotes < 1 }
            or: { tuplet.normalNotes.isKindOf(Integer).not }
            or: { tuplet.normalNotes < 1 }) {
            Error("Validator: % prints %:%, which is not a number of notes on "
                "both sides. A bracket is so many notes in the time of so "
                "many.".format(tuplet, tuplet.actualNotes, tuplet.normalNotes)).throw
        };
        // See Note [A bracket is two facts] in MusicScore.sc.
        if ((tuplet.normalNotes * ratio.denominator)
            != (tuplet.actualNotes * ratio.numerator)) {
            Error("Validator: % prints %:% but scales time by %. The number over "
                "a bracket and the time it scales are the same fact said twice, "
                "so they cannot disagree.".format(
                    tuplet, tuplet.actualNotes, tuplet.normalNotes, ratio)).throw
        };
        if (tuplet.children.isEmpty and: { tuplet.isTrivial.not }) {
            Error("Validator: % brackets nothing. An empty tuplet has no time to "
                "scale and no notes to bracket.".format(tuplet)).throw
        };
        ^this
    }

    // A Voice is a timeline inside a bar, so it has to be in one. Loose, its
    // leaves have no barline to be measured from, and a writer handed one
    // emits notes with no measure around them, which MusicXML importers read as
    // a broken part rather than as an error.
    *prCheckVoicePlacement { |voice|
        if (voice.parent.isKindOf(Measure).not) {
            Error("Validator: a Voice held by % is outside any bar. A voice is one "
                "timeline within a measure, so it only means anything inside "
                "one.".format(voice.parent ?? { "nothing" })).throw
        };
        ^this
    }

    // The spine's shape, from the other direction: a score is made of staves
    // and a staff is made of bars.
    //
    // Checked as well as the placement rules below, because the two catch
    // different things. Placement asks what holds an element. This asks what an
    // element holds, and only the second sees a leaf hung straight off a
    // staff, which has no placement rule of its own. Anything that walks a
    // staff bar by bar, here or in preparation, steps straight over such a
    // child, so a tie dangling inside it would never be looked at.
    *prCheckContents { |container|
        if (container.isKindOf(MusicScore)) {
            container.children.do { |child|
                if (child.isKindOf(Staff).not) {
                    Error("Validator: a score holds a %. A score is made of "
                        "staves.".format(child.class)).throw
                }
            };
            ^this
        };
        if (container.isKindOf(Staff)) {
            container.children.do { |child|
                if (child.isKindOf(Measure).not) {
                    Error("Validator: a staff holds a %. A staff is made of bars; "
                        "anything else has no barline to be laid out against, and "
                        "the passes that read a staff bar by bar would step over "
                        "it.".format(child.class)).throw
                }
            };
            ^this
        };

        // Below a staff the structural containers have no place. Only the top
        // two levels were stated, which left every level under them open: a bar
        // could hold a whole score, and both the validator and the writers
        // carried it along.
        container.children.do { |child|
            if (child.isKindOf(MusicScore) or: { child.isKindOf(Staff) }
                or: { child.isKindOf(Measure) }) {
                Error("Validator: a % holds a %. A score, a staff and a bar each "
                    "sit at one depth, and one nested inside the music is "
                    "somewhere no writer lays out and no pass reaches."
                    .format(container.class, child.class)).throw
            }
        };
        ^this
    }

    // A bar belongs to a staff, or stands alone as the thing being checked.
    // Hung anywhere else (beside the staves of a score, say), it is in a place
    // no writer lays out and no preparation pass reaches, so it would be
    // carried along unexamined.
    *prCheckMeasurePlacement { |measure|
        var holder = measure.parent;
        if (holder.notNil and: { holder.isKindOf(Staff).not }) {
            Error("Validator: a % bar is held by a %. A measure belongs to a staff, "
                "or stands on its own; anywhere else nothing lays it out.".format(
                    measure.meter, holder.class)).throw
        };
        ^this
    }

    *prCheckMeasure { |measure|
        this.prCheckMeasurePlacement(measure);
        if (measure.mixesVoicesWithElements) {
            Error("Validator: a % bar mixes voices with loose elements. A bar is "
                "either one timeline or a set of voices, not both.".format(
                    measure.meter)).throw
        };
        // A full bar must match its meter. A partial one must match the span it
        // declares. Short is only legal when the bar says so. A bar that is
        // merely short is the error this has always caught, and stays one.
        measure.voices.do { |voice|
            if ((voice.duration == measure.barDuration).not) {
                Error("Validator: a % bar declares % of music and a voice holds %. "
                    "Each voice must fill the bar's own span independently. A bar "
                    "that is short without saying so is an error; use "
                    "Measure.pickup or Measure.partial to declare one.".format(
                        measure.meter, measure.barDuration, voice.duration)).throw
            }
        };
        this.prCheckDirectionOffsets(measure);
        ^this
    }

    // A direction's offset is local to its bar, and two things have to hold
    // that `Direction` itself cannot ask, never having been shown a Measure.
    //
    // Inside the bar, first. An offset equal to the bar's span names the
    // downbeat of the next bar, which is a different bar and should be
    // written there, so the test is strictly less than. `barDuration` rather
    // than the meter's span, so a pickup is measured by what it actually holds.
    //
    // Then: somewhere a writer can put it, by Note [Where a direction may sit]
    // in ScoreWriter.sc.
    *prCheckDirectionOffsets { |measure|
        var boundaries;
        if (measure.directions.every { |direction| direction.atBarStart }) { ^this };
        boundaries = this.prLocalBoundariesIn(measure);
        measure.directions.do { |direction|
            if (direction.offset >= measure.barDuration) {
                Error("Validator: \"%\" is written % into a % bar that lasts %. An "
                    "offset must fall inside its own bar; one at the span names "
                    "the downbeat of the next bar, which is a different bar to "
                    "write it on.".format(direction.text, direction.offset,
                        measure.meter, measure.barDuration)).throw
            };
            if (boundaries.any { |at| at == direction.offset }.not) {
                Error("Validator: \"%\" is written % into a % bar, which is not "
                    "where anything begins. A mark sits between leaves, in every "
                    "voice at once. This bar begins something at %.".format(
                        direction.text, direction.offset, measure.meter,
                        boundaries.collect { |at| at.asString }.join(", "))).throw
            };
        };
        ^this
    }

    // Where the note stream is between leaves, local to the bar and in every
    // timeline at once.
    //
    // Normalized by `metricOffset`: `leafOffsetsIn` measures from where the bar
    // sits in its meter, which for a pickup is not zero. Its first leaf comes
    // back at 3/4 in a quarter-note anacrusis to 4/4. A direction's offset is
    // local to the bar, so comparing the two directly would refuse the default
    // offset of zero on exactly the bars where nobody would look for it.
    //
    // Every timeline, not the first: which voice a writer walks first is a
    // writer's business, and a rule that depended on it would give two answers
    // for one score.
    *prLocalBoundariesIn { |measure|
        var perVoice = measure.voices.collect { |voice|
            voice.leaves.collect { |leaf|
                ScorePrepare.leafOffsetsIn(measure)[leaf] - measure.metricOffset
            }
        };
        ^perVoice.reduce { |a, b|
            a.select { |at| b.any { |other| other == at } }
        } ? []
    }

    *prCheckChord { |chord|
        if (chord.pitches.isEmpty) {
            Error("Validator: a chord with no pitches. It occupies time but "
                "sounds nothing - write a rest instead.").throw
        };
        // One pitch, once: a repeated one has two tie flags with no way to say
        // which a continuation belongs to, so the two runs merge and the chord
        // sounds longer than it was written.
        chord.pitches.do { |pitch, index|
            if (index > 0 and: {
                chord.pitches.copyRange(0, index - 1).any { |other| other == pitch }
            }) {
                Error("Validator: % appears twice in %. A chord ties per pitch, so "
                    "a repeated one cannot say which tie a continuation belongs "
                    "to. For doubling at the unison, give each its own "
                    "Voice.".format(pitch, chord)).throw
            }
        };
        ^this
    }

    // A whole-bar rest is drawn as the bar's silence rather than as a note
    // value, so both formats spell one whose length no note head could, a
    // silent bar of 5/8, say. It is the one leaf a prepared tree may hold that
    // is not notatable, and demanding it would refuse the only case that does
    // not need it.
    // Note [What a grace group may hold]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // Notes and chords, each spellable as one note head, and nothing else.
    //
    // Two of the shapes metasonic-score has to refuse cannot be built here at
    // all, which is a property of hanging the group off a leaf rather than
    // wrapping one. Its `EmptyGrace` needs a group that exists and is empty,
    // where an empty list here simply means a leaf with no graces. Its
    // `NestedGrace` and its tuplet host both need a host that is not a leaf,
    // and only a leaf has a `graces` list. So what is left to check is what a
    // group contains.
    //
    // A rest is refused because a grace group is an ornament, and a silent
    // ornament is nothing at all. Ties, markings and spanners are refused
    // because the model carries them and this slice's writers do not spell
    // them, and a decoration that reaches the tree and not the page is worse
    // than one the model declines to hold. A spanner would be doubly wrong: the
    // pairing walks `leaves`, a grace leaf is not one, so an endpoint here
    // could never find its other end.
    *prCheckGraces { |leaf|
        if (leaf.hasGraces.not) { ^this };
        leaf.graces.do { |grace|
            var what = case
                { grace.isKindOf(MusicNote) or: { grace.isKindOf(Chord) } } { nil }
                { grace.isKindOf(MusicRest) } { "a rest, and an ornament is a sound" }
                { true } { "a %, and a group holds notes and chords"
                    .format(grace.class) };

            if (what.notNil) {
                Error("Validator: a grace group on % holds %."
                    .format(leaf.class, what)).throw
            };
            if (grace.dur.isNotatable.not) {
                Error("Validator: a grace note's % is a display value and has "
                    "to be one note head.".format(grace.dur)).throw
            };
            if (grace.hasGraces) {
                Error("Validator: a grace note carries a grace group of its "
                    "own, and a grace group ornaments a sounding note.").throw
            };
            // `prTiedPitchesOf` rather than reading `tiesToNext`, which is a
            // flag on a note and a mask on a chord.
            if (this.prTiedPitchesOf(grace).notEmpty) {
                Error("Validator: a grace note ties onward. A tie out of a "
                    "grace group is not in this slice.").throw
            };
            if (grace.hasMarkings or: { grace.hasSpanners }) {
                Error("Validator: a grace note carries a marking or a spanner, "
                    "which no writer here spells yet.").throw
            };
        };
        ^this
    }

    *prCheckNotatable { |leaf, wholeBarRest = false|
        if (wholeBarRest) { ^this };
        if (leaf.dur.isNotatable.not) {
            Error("Validator: % cannot be written as one note head. Run "
                "ScorePrepare.run first.".format(leaf.dur)).throw
        };
        ^this
    }

    *prCheckParents { |container|
        container.children.do { |child|
            if (child.parent !== container) {
                Error("Validator: % is held by % but points at % as its parent. "
                    "Sounding durations are read through that chain, so a stale "
                    "link makes them silently wrong.".format(
                        child, container, child.parent)).throw
            }
        };
        ^this
    }

    // Ties resolve within one voice of one bar, because that is as far as
    // preparation reaches and because a tie into a different voice is not a
    // tie. A tree with measures is checked voice by voice, so a tie left open
    // at a barline is caught. One without them is checked end to end.
    *prCheckTies { |element|
        var staves = List.new, measures = List.new;
        element.traverse { |node|
            if (node.isKindOf(Staff)) { staves.add(node) };
            if (node.isKindOf(Measure)) { measures.add(node) };
        };
        staves.do { |staff| this.prCheckStaffTies(staff) };

        // Bars inside a staff were covered above, as one run each. A bar not
        // under a staff is the other legitimate shape, a lone Measure being
        // checked on its own. It has no next bar to continue into, so a
        // tie left open at its end is dangling.
        //
        // Both are checked rather than one being chosen: returning early on the
        // first would make the other's coverage depend on what the tree
        // happened to contain.
        measures.do { |measure|
            if (this.prStaffAbove(measure).isNil) {
                measure.voices.do { |voice|
                    this.prCheckTieRun(voice.leaves, "the bar ends with nothing after it")
                }
            }
        };

        if (staves.isEmpty and: { measures.isEmpty }) {
            this.prCheckTieRun(element.leaves, "the music ends")
        };
        ^this
    }

    // Spanners pair a start with a stop, so they are checked over the same runs
    // ties are, one per voice of a staff, or one per voice of a lone bar. That
    // is what makes a slur crossing into another voice, staff or part an error
    // rather than something a writer has to notice: the run simply ends with
    // the slur still open.
    *prCheckSpanners { |element|
        var staves = List.new, measures = List.new;
        element.traverse { |node|
            if (node.isKindOf(Staff)) { staves.add(node) };
            if (node.isKindOf(Measure)) { measures.add(node) };
        };
        staves.do { |staff|
            var streams = Dictionary.new;
            staff.children.do { |bar|
                if (bar.isKindOf(Measure)) {
                    bar.voices.do { |voice, index|
                        streams[index] =
                            (streams[index] ?? { List.new }).addAll(voice.leaves)
                    }
                }
            };
            streams.keys.asArray.sort.do { |index|
                this.prCheckSpannerRun(streams[index].asArray, "the staff ends")
            }
        };
        measures.do { |measure|
            if (this.prStaffAbove(measure).isNil) {
                measure.voices.do { |voice|
                    this.prCheckSpannerRun(voice.leaves, "the bar ends")
                }
            }
        };
        if (staves.isEmpty and: { measures.isEmpty }) {
            this.prCheckSpannerRun(element.leaves, "the music ends")
        };
        ^this
    }

    *prCheckSpannerRun { |leaves, ending|
        var open = Dictionary.new;
        leaves.do { |leaf|
            // Stops before starts on one leaf: a slur may end and another begin
            // on the same note, and that is not the same id reopening.
            leaf.spannerStops.do { |endpoint|
                var key = this.prSpannerKey(endpoint);
                if (open[key].isNil) {
                    Error("Validator: a % stop with id % on % closes nothing. A "
                        "spanner ends where one of the same id began.".format(
                            endpoint.kind, endpoint.id, leaf)).throw
                };
                open.removeAt(key);
            };
            leaf.spannerStarts.do { |endpoint|
                var key = this.prSpannerKey(endpoint);
                // Some kinds do not overlap, whatever their ids, and the
                // endpoint says which. See Note [Kinds that cannot overlap] in
                // Spanner.sc.
                if (endpoint.permitsOverlap.not and: {
                    open.keys.asArray.any { |k|
                        k.beginsWith(endpoint.kind.asString ++ "#") }
                }) {
                    Error("Validator: a % is already open when % starts another. "
                        "A % does not overlap another - close the first one before "
                        "opening the next.".format(
                            endpoint.kind, leaf, endpoint.kind)).throw
                };
                if (open[key].notNil) {
                    Error("Validator: a % with id % is already open when % starts "
                        "another. Give overlapping spanners different ids, so "
                        "each stop can be told which start it closes.".format(
                            endpoint.kind, endpoint.id, leaf)).throw
                };
                open[key] = leaf;
            };
        };
        if (open.notEmpty) {
            Error("Validator: % left open when %. A spanner needs both ends, and "
                "it cannot reach into another voice, staff or part.".format(
                    open.keys.asArray, ending)).throw
        };
        ^this
    }

    // See Note [What a beam may hold]. Runs before the generic spanner check,
    // so a beam crossing a barline is told that rather than told a spanner was
    // left open somewhere in the staff.
    *prCheckBeams { |element|
        var measures = List.new;
        element.traverse { |node|
            if (node.isKindOf(Measure)) { measures.add(node) };
            if (node.isLeaf) { this.prCheckBeamEndpoints(node) };
        };
        if (measures.isEmpty) {
            this.prCheckBeamRun(element.leaves, "the music ends");
            ^this
        };
        measures.do { |measure|
            measure.voices.do { |voice|
                this.prCheckBeamRun(voice.leaves, "the bar ends")
            }
        };
        ^this
    }

    // What is wrong with one endpoint on its own, asked of every leaf so it
    // holds outside a bar too.
    *prCheckBeamEndpoints { |leaf|
        var beams = leaf.spanners.select { |endpoint| endpoint.isBeam };
        if (beams.isEmpty) { ^this };
        if (leaf.isKindOf(MusicRest)) {
            Error("Validator: % carries a beam endpoint. A beam joins note heads "
                "and a rest has none - a rest breaks a beam rather than beginning "
                "or ending one.".format(leaf)).throw
        };
        // Two groups meeting on one note is a disagreement about where the
        // group is, not a notation: if the note is beamed to both sides it is
        // one beam. Refusing it here is also what makes "a beam needs two
        // notes" hold: a run cannot open and close on the same leaf, so that
        // is not checked separately, because it could not be made to fail.
        if (beams.any { |endpoint| endpoint.isStart }
            and: { beams.any { |endpoint| endpoint.isStop } }) {
            Error("Validator: % both ends a beam and begins one. A note belongs to "
                "one beam group or to none.".format(leaf)).throw
        };
        ^this
    }

    // \beam is nonOverlapping, so one beam is open at a time and a run is a
    // single open id rather than a dictionary of them.
    *prCheckBeamRun { |leaves, ending|
        var open, level;
        leaves.do { |leaf|
            var stops = leaf.spannerStops.select { |endpoint| endpoint.isBeam };
            var starts = leaf.spannerStarts.select { |endpoint| endpoint.isBeam };
            if (open.notNil) {
                this.prCheckBeamable(leaf);
                level = this.prCheckBeamLevel(leaf, level);
            };
            stops.do { |endpoint|
                if (open.isNil) {
                    Error("Validator: a beam stop with id % on % closes nothing. A "
                        "beam ends in the bar it began in.".format(
                            endpoint.id, leaf)).throw
                };
                if (open != endpoint.id) {
                    Error("Validator: a beam stop with id % on % closes a beam with "
                        "id %. The two ends of a beam carry the same id.".format(
                            endpoint.id, leaf, open)).throw
                };
                open = nil;
            };
            starts.do { |endpoint|
                if (open.notNil) {
                    Error("Validator: a beam is already open when % starts another. "
                        "A note belongs to one beam group - close the first before "
                        "opening the next.".format(leaf)).throw
                };
                open = endpoint.id;
                this.prCheckBeamable(leaf);
                level = leaf.dur.flags;
            };
        };
        if (open.notNil) {
            Error("Validator: a beam with id % is still open when %. Rastrum "
                "keeps a beam inside one bar and one voice. A beam across a "
                "barline is ordinary notation this quark does not write yet."
                .format(open, ending)).throw
        };
        ^this
    }

    // Written duration, not sounding: an eighth inside a triplet is still an
    // eighth on the page, and it is the flag on the page a beam replaces.
    *prCheckBeamable { |leaf|
        if (leaf.isKindOf(MusicRest)) {
            Error("Validator: a beam runs through %, which is a rest. A rest breaks "
                "a beam - end the beam before it and begin another after.".format(
                    leaf)).throw
        };
        if (leaf.dur < Duration.quarter) { ^this };
        Error("Validator: a beam runs through %, which is written as % and carries "
            "no flag. A beam is the flags of its notes joined, so everything under "
            "one is shorter than a quarter.".format(leaf, leaf.dur)).throw
    }

    // Returns the level, so a run can carry it forward. See
    // Note [What a beam may hold] for why a mixed group is refused.
    //
    // An unwritable duration answers nil rather than a count: it is already an
    // error, reported where the note cannot be spelled at all, and repeating it
    // here would say the less useful of the two things.
    *prCheckBeamLevel { |leaf, level|
        var flags = leaf.dur.flags;
        if (flags.isNil or: { level.isNil }) { ^level };
        if (flags == level) { ^level };
        Error("Validator: % needs % beams where the group has %. Every note under "
            "one beam carries the same number here. Mixing them needs partial "
            "beams, which are not built yet - split the group, or give the notes "
            "the same value.".format(leaf, flags, level)).throw
    }

    *prSpannerKey { |endpoint| ^(endpoint.kind.asString ++ "#" ++ endpoint.id) }

    *prStaffAbove { |node|
        var above = node.parent;
        while { above.notNil } {
            if (above.isKindOf(Staff)) { ^above };
            above = above.parent;
        };
        ^nil
    }

    // Within a staff a tie may cross a barline, so each voice is one run from
    // the first bar to the last rather than a fresh run per bar. Voices are
    // matched across barlines by position, the same rule preparation uses. A
    // tie continues within its own timeline and nowhere else.
    *prCheckStaffTies { |staff|
        var streams = Dictionary.new;
        staff.children.do { |bar|
            if (bar.isKindOf(Measure)) {
                bar.voices.do { |voice, index|
                    streams[index] = (streams[index] ?? { List.new }).addAll(voice.leaves)
                }
            }
        };
        streams.keys.asArray.sort.do { |index|
            this.prCheckTieRun(streams[index].asArray, "the staff ends")
        };
        ^this
    }

    *prCheckTieRun { |leaves, ending|
        var pending = [];
        leaves.do { |leaf|
            var arriving = this.prPitchesOf(leaf);
            pending.do { |pitch|
                if (arriving.any { |p| p == pitch }.not) {
                    Error("Validator: % ties to the next leaf, but % does not "
                        "carry it. A tie continues one pitch into the next "
                        "note.".format(pitch, leaf)).throw
                }
            };
            pending = this.prTiedPitchesOf(leaf);
        };
        if (pending.notEmpty) {
            Error("Validator: % ties onward, but %. A tie may cross a barline into "
                "the same voice of the next bar, but it must reach some note.".format(
                    pending, ending)).throw
        };
        ^this
    }

    *prPitchesOf { |leaf|
        if (leaf.isKindOf(Chord)) { ^leaf.pitches };
        if (leaf.isKindOf(MusicNote)) { ^[leaf.pitch] };
        ^[]
    }

    *prTiedPitchesOf { |leaf|
        if (leaf.isKindOf(Chord)) { ^leaf.tiedPitches };
        if (leaf.isKindOf(MusicNote)) { ^if (leaf.tiesToNext) { [leaf.pitch] } { [] } };
        ^[]
    }
}
