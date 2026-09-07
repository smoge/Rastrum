// Read-only tree view for score elements and RhythmCell. Prints
// stored shape and receiver-relative paths. Prepares, validates and
// writes nothing.

RastrumTreeDisplay {
    // Closed option vocabulary.
    //
    // Unknown names are refused instead of ignored.
    classvar <optionNames, <attachmentModes, cellOptionNames, scoreOptionNames;

    *initClass {
        cellOptionNames = [\span];
        scoreOptionNames = [\attachments];
        optionNames = [\paths, \maxDepth, \maxChildren]
            ++ scoreOptionNames ++ cellOptionNames;
        // A summary sits in the owner's label. A sidecar gets its own row.
        attachmentModes = [\summary, \sidecars, \none];
    }

    // Refusal hints match the receiver. `attachments` is score-only
    // and `span` is cell-only.
    *prOptionNamesFor { |value|
        var elsewhere = if (value.isKindOf(RhythmCell)) {
            scoreOptionNames
        } {
            cellOptionNames
        };
        ^optionNames.reject { |name| elsewhere.includes(name) }
    }

    // >>> RastrumTreeDisplay.string(Measure("1/4", "c4")).endsWith("1/4\n")   -> true
    *string { |value, options|
        ^String.streamContents { |stream|
            this.rows(value, options).do { |row| stream << row[\line] << "\n" }
        }
    }

    // Answers the value for debug chains.
    *post { |value, options|
        this.string(value, options).post;
        ^value
    }

    // Structured rows under `string`: nodes, guides, truncations and sidecars.
    //
    // >>> RastrumTreeDisplay.rows(Measure("2/4", "c4 r4")).last[\path]   -> [ 1 ]
    *rows { |value, options|
        var settings;
        var rows = List.new;
        this.prCheckedValue(value);
        settings = this.prSettings(options, value);
        this.prWalk(value, rows, [], "", true, true, settings);
        ^rows.asArray
    }

    // A selection prints its source tree with held rows marked.
    *selectionString { |selection, options|
        ^String.streamContents { |stream|
            this.selectionRows(selection, options).do { |row|
                stream << row[\line] << "\n" }
        }
    }

    *selectionPost { |selection, options|
        this.selectionString(selection, options).post;
        ^selection
    }

    *selectionRows { |selection, options|
        var source = this.prCheckedSelection(selection);
        var rows = List.new;
        var settings = this.prSettings(options, source);

        // Mark by path, so selection order does not affect the picture.
        settings[\selected] = Dictionary.new;
        selection.records.do { |record|
            settings[\selected][record[\path]] = true };
        this.prWalk(source, rows, [], "", true, true, settings);
        ^rows.asArray
    }

    *prCheckedSelection { |selection|
        if (selection.source.isNil) {
            Error(
                "RastrumTreeDisplay: the selection carries no tree to print. Read one with ScoreSelection(score)."
            ).throw
        };
        ^this.prCheckedValue(selection.source)
    }

    *prCheckedValue { |value|
        if (value.isKindOf(ScoreElement).not
            and: { value.isKindOf(RhythmCell).not }) {
            Error(
                "RastrumTreeDisplay: % is neither a ScoreElement nor a RhythmCell. This view reads those two trees."
                .format(value.class)
            ).throw
        };
        ^value
    }

    *prSettings { |options, value|
        var settings = (paths: true, attachments: \summary,
            maxDepth: inf, maxChildren: inf);
        if (options.notNil and: { options.isKindOf(Dictionary).not }) {
            Error(
                "RastrumTreeDisplay: options must be an Event of display settings, got a %."
                .format(options.class)
            ).throw
        };
        (options ? Event.new).keysValuesDo { |key, setting|
            if (optionNames.includes(key).not) {
                Error(
                    "RastrumTreeDisplay: % is not an option. Use one of %."
                    .format(
                        key.asCompileString,
                        this.prOptionNamesFor(value)
                        .collect { |each| each.asString }
                        .join(", ")
                    )
                ).throw
            };
            // fail at the option edge before one tree ignores the
            // other's setting.
            if (cellOptionNames.includes(key)
                and: { value.isKindOf(RhythmCell).not }) {
                Error(
                    "RastrumTreeDisplay: % is a rhythm cell's option, and this is a %."
                    .format(
                        key.asCompileString,
                        value.class
                    )
                ).throw
            };
            if (scoreOptionNames.includes(key)
                and: { value.isKindOf(RhythmCell) }) {
                Error(
                    "RastrumTreeDisplay: % is a score tree's option, and this is a %."
                    .format(
                        key.asCompileString,
                        value.class
                    )
                ).throw
            };
            settings[key] = setting
        };
        if (settings[\paths].isKindOf(Boolean).not) {
            Error(
                "RastrumTreeDisplay: paths must be true or false, got a %."
                .format(settings[\paths].class)
            ).throw
        };
        if (attachmentModes.includes(settings[\attachments]).not) {
            Error(
                "RastrumTreeDisplay: % is not an attachment mode. Use one of %."
                .format(
                    settings[\attachments].asCompileString,
                    attachmentModes.collect { |each| each.asString }
                    .join(", ")
                )
            ).throw
        };
        this.prCheckedLimit(settings[\maxDepth], "maxDepth");
        this.prCheckedLimit(settings[\maxChildren], "maxChildren");
        if (settings[\span].notNil) {
            settings[\span] = this.prCheckedSpan(settings[\span]);
            // Terminal rows only: this is what `segmentsIn` answers.
            settings[\segments] = value.segmentsIn(settings[\span])
        };
        ^settings
    }

    // Rename RhythmTree's coercion failure under this option.
    *prCheckedSpan { |span|
        var duration, failure;
        try { duration = RhythmTree.spanOf(span) } { |error| failure = error };
        if (failure.notNil) {
            Error(
                "RastrumTreeDisplay: span must be a Meter or a duration, got %."
                .format(span.asCompileString)
            ).throw
        };
        if (duration <= Duration(0, 1)) {
            Error(
                "RastrumTreeDisplay: span must be positive, got %."
                .format(duration)
            ).throw
        };
        ^duration
    }

    *prCheckedLimit { |value, name|
        if (value == inf) { ^value };
        if (value.isKindOf(Integer).not or: { value < 0 }) {
            Error(
                "RastrumTreeDisplay: % must be inf or a non-negative Integer, got %."
                .format(name, value.asCompileString)
            ).throw
        };
        ^value
    }

    // Walk score children and cell share Events with guide prefixes.
    *prWalk { |node, rows, path, prefix, isLast, isRoot, settings|
        var children = this.prChildrenOf(node);
        var depth = path.size;
        var bodyPrefix = prefix ++ if (isRoot or: { isLast }) { "   " } { "|  " };
        var childPrefix = if (isRoot) { "" } { bodyPrefix };
        var connector = if (isRoot) { "" } {
            if (isLast) { "`- " } { "+- " }
        };
        var label = this.prLabel(node, path, isRoot, settings);
        var shown = if (depth >= settings[\maxDepth]) { 0 } {
            children.size.min(settings[\maxChildren]).asInteger
        };
        var hidden = children.size - shown;
        var row = (
            rowKind: \node,
            pathKind: this.prPathKindOf(node, isRoot),
            selected: this.prIsSelected(path, settings),
            path: path,
            element: this.prElementOf(node),
            depth: depth,
            label: label,
            line: prefix ++ connector ++ label);

        // A cell root is display-only. `shareAt` and `cellAt` have no
        // empty-path case.
        if (isRoot and: { node.isKindOf(RhythmCell) }) {
            row[\path] = nil;
            row[\displayPath] = "@root"
        };
        rows.add(row);
        if (settings[\attachments] == \sidecars) {
            this.prSidecarRows(node, row, bodyPrefix, depth, settings)
                .do { |each| rows.add(each) }
        };

        shown.do { |index|
            rows.add(this.prGuideRow(childPrefix, depth + 1));
            this.prWalk(children[index], rows, path ++ [index], childPrefix,
                (index == (shown - 1)) and: { hidden == 0 }, false, settings)
        };
        if (hidden > 0) {
            rows.add(this.prGuideRow(childPrefix, depth + 1));
            rows.add(this.prTruncationRow(childPrefix, row, depth + 1,
                hidden, children.size, depth >= settings[\maxDepth], settings))
        }
    }

    // Selection marks node rows only.
    *prIsSelected { |path, settings|
        ^(settings[\selected] ? Dictionary.new)[path] == true
    }

    *prPathKindOf { |node, isRoot|
        if (isRoot) { ^\displayRoot };
        if (node.isKindOf(ScoreElement)) { ^\scorePath };
        ^\cellPath
    }

    // Score rows hold elements. Cell rows hold raw shares.
    *prElementOf { |node|
        if (node.isKindOf(Event)) { ^node[\share] };
        ^node
    }

    // Explicit by tree kind. Ordinary `children` has wider meanings in sclang.
    *prChildrenOf { |node|
        if (node.isKindOf(ScoreContainer)) { ^node.children.asArray };
        if (node.isKindOf(ScoreElement)) { ^[] };
        if (node.isKindOf(RhythmCell)) { ^this.prShareNodes(node.proportions) };
        if (node[\share].isNumber) { ^[] };
        ^this.prShareNodes(node[\share][1])
    }

    // RhythmCell stores raw share values, so the walker needs an Event row.
    *prShareNodes { |list|
        ^list.collect { |each| (share: each) }
    }

    // Spacer between siblings keeps ancestor guides visible.
    *prGuideRow { |childPrefix, depth|
        ^(rowKind: \guide, depth: depth, line: childPrefix ++ "|")
    }

    // Explicit truncation row. `ownerDisplayPath` covers `@root`.
    *prTruncationRow { |childPrefix, owner, depth, hidden, total, byDepth,
        settings|
        var label = if (byDepth) {
            "... % children not shown (maxDepth %)".format(
                hidden, settings[\maxDepth])
        } {
            "... % of % children not shown (maxChildren %)".format(
                hidden, total, settings[\maxChildren])
        };
        ^(rowKind: \truncation, ownerPath: owner[\path],
            ownerDisplayPath: owner[\displayPath], omitted: hidden,
            depth: depth, label: label, line: childPrefix ++ "`- " ++ label)
    }

    // Attachment rows keep the owner's address. The row itself has no path.
    *prSidecarRows { |node, owner, bodyPrefix, depth, settings|
        ^this.prSidecarsOf(node, settings).collect { |each|
            var label = each[0] ++ " " ++ each[1];
            (rowKind: \sidecar, path: nil, ownerPath: owner[\path],
                ownerDisplayPath: owner[\displayPath], displayPath: each[0],
                element: each[2], depth: depth + 1,
                label: label, line: bodyPrefix ++ label)
        }
    }

    *prLabel { |node, path, isRoot, settings|
        var body = this.prBody(node, path, settings);
        if (settings[\attachments] == \summary) {
            body = body ++ this.prSummaryOf(node, settings)
        };
        if (this.prIsSelected(path, settings)) { body = body ++ " selected" };
        if (settings[\paths].not) { ^body };
        if (isRoot and: { node.isKindOf(RhythmCell) }) { ^"@root " ++ body };
        ^this.prPathText(path) ++ " " ++ body
    }

    // >>> RastrumTreeDisplay.prPathText([0, 2])   -> [0,2]
    *prPathText { |path| ^"[" ++ path.join(",") ++ "]" }

    // Labels show musical state, not constructor source.
    *prBody { |node, path, settings|
        var body = case
            { node.isKindOf(RhythmCell) }  { this.prCellBody(node, settings) }
            { node.isKindOf(Event) }       { this.prShareBody(node, path, settings) }
            { node.isKindOf(MusicScore) }  { this.prScoreBody(node) }
            { node.isKindOf(Staff) }       { this.prStaffBody(node) }
            { node.isKindOf(Measure) }     { this.prMeasureBody(node) }
            { node.isKindOf(Voice) }       { this.prVoiceBody(node) }
            { node.isKindOf(Tuplet) }      { this.prTupletBody(node) }
            { node.isKindOf(MusicNote) }   { this.prNoteBody(node) }
            { node.isKindOf(MusicRest) }   { this.prRestBody(node) }
            { node.isKindOf(Chord) }       { this.prChordBody(node) }
            { true } { node.class.name.asString ++ this.prTimes(node) };
        ^body
    }

    // Cell labels use display words, not RTM syntax.
    *prCellBody { |cell, settings|
        if (settings[\span].notNil) {
            ^"RhythmCell span: " ++ this.prTime(settings[\span])
        };
        ^"RhythmCell shares: " ++ cell.size
    }

    // A negative head still divides. Only a negative terminal share rests.
    *prShareBody { |node, path, settings|
        var share = node[\share];
        if (share.isNumber.not) { ^"divide " ++ share[0] };
        if (share < 0) { ^"rest " ++ share.abs ++ this.prSegmentText(path, settings) };
        ^"share " ++ share ++ this.prSegmentText(path, settings)
    }

    // Time rows come from `segmentsIn`, which reports terminal shares only.
    *prSegmentText { |path, settings|
        var row = settings[\segments] !? { |segments|
            segments.detect { |each| each[\path] == path }
        };
        if (row.isNil) { ^"" };
        ^" offset: " ++ this.prTime(row[\offset])
            ++ " duration: " ++ this.prTime(row[\duration])
    }

    *prScoreBody { |score|
        var text = "Score";
        if (score.title.notNil) {
            text = text ++ " " ++ score.title.asCompileString
        };
        if (score.composer.notNil) {
            text = text ++ " composer: " ++ score.composer.asCompileString
        };
        ^text ++ " staves: " ++ score.children.size
    }

    *prStaffBody { |staff|
        var text = "Staff";
        if (staff.name.notNil) {
            text = text ++ " " ++ staff.name.asCompileString
        };
        if (staff.shortName.notNil) {
            text = text ++ " short: " ++ staff.shortName.asCompileString
        };
        if (staff.clef.notNil) { text = text ++ " clef: " ++ staff.clef };
        ^text ++ " bars: " ++ staff.children.size
    }

    // A partial bar is its held span plus metric offset.
    *prMeasureBody { |measure|
        ^"Measure " ++ measure.meter.count ++ "/" ++ measure.meter.unit
            ++ " holds: " ++ this.prTime(measure.barDuration)
            ++ " offset: " ++ this.prTime(measure.metricOffset)
    }

    *prVoiceBody { |voice|
        var text = "Voice";
        if (voice.name.notNil) { text = text ++ " " ++ voice.name.asCompileString };
        ^text ++ this.prTimes(voice)
    }

    // Both times always, because the pair is what the bracket means.
    *prTupletBody { |tuplet|
        ^"Tuplet " ++ tuplet.actualNotes ++ ":" ++ tuplet.normalNotes
            ++ " " ++ this.prTime(tuplet.duration)
            ++ "=>" ++ this.prTime(tuplet.prolatedDuration)
    }

    *prNoteBody { |note|
        var text = "Note " ++ this.prPitchText(note.pitch)
            ++ this.prTimes(note);
        if (note.tiesToNext) { text = text ++ " tied" };
        ^text
    }

    *prRestBody { |rest| ^"Rest" ++ this.prTimes(rest) }

    *prChordBody { |chord|
        var text = "Chord <"
            ++ chord.pitches.collect { |each| this.prPitchText(each) }.join(" ")
            ++ ">" ++ this.prTimes(chord);
        if (chord.tiesAnything) {
            text = text ++ " ties: " ++ if (chord.tiesAll) { "all" } {
                chord.tiesToNext.asCompileString
            }
        };
        ^text
    }

    // ---- attachments -----
    //
    // Attachments are facts beside a node, never children.

    // [display path, text, value]
    *prSidecarsOf { |node, settings|
        var out = [];
        if (node.isKindOf(Measure)) {
            node.clef !? { out = out.add(["@clef", node.clef.asString, node.clef]) };
            node.directions.do { |each, index|
                out = out.add(["@directions[" ++ index ++ "]",
                    this.prDirectionText(each), each])
            };
            ^out
        };
        if (node.isKindOf(ScoreLeaf).not) { ^out };
        node.markings.do { |each, index|
            out = out.add(["@markings[" ++ index ++ "]",
                this.prMarkingSidecarText(each), each])
        };
        node.spanners.do { |each, index|
            out = out.add(["@spanners[" ++ index ++ "]",
                this.prSpannerText(each), each])
        };
        // One style covers the whole group, so it is said once.
        if (node.hasGraces and: { node.graceStyle != \grace }) {
            out = out.add(["@graceStyle", node.graceStyle.asString, node.graceStyle])
        };
        node.graces.do { |each, index|
            out = out.add(["@graces[" ++ index ++ "]",
                this.prGraceText(each, settings), each])
        };
        ^out
    }

    // The same facts, compact enough to sit at the end of the owner's label.
    *prSummaryOf { |node, settings|
        var text = "";
        if (node.isKindOf(Measure)) {
            node.clef !? { text = text ++ " clef: " ++ node.clef };
            if (node.hasDirections) {
                text = text ++ " dirs: " ++ node.directions.collect { |each|
                    this.prDirectionText(each) }.join(", ")
            };
            ^text
        };
        if (node.isKindOf(ScoreLeaf).not) { ^text };
        if (node.hasMarkings) {
            text = text ++ " marks: " ++ node.markings.collect { |each|
                this.prMarkingText(each) }.join(", ")
        };
        if (node.hasSpanners) {
            text = text ++ " spanners: " ++ node.spanners.collect { |each|
                this.prSpannerText(each) }.join(", ")
        };
        if (node.hasGraces) {
            text = text ++ " graces: " ++ node.graces.size;
            if (node.graceStyle != \grace) {
                text = text ++ " " ++ node.graceStyle
            }
        };
        ^text
    }

    // A sforzando prints the word a score spells it with, since its
    // stored value is a level and would read as a dynamic on its own.
    //
    // >>> RastrumTreeDisplay.prMarkingText(Marking.sforzando(\f))   -> sfz
    *prMarkingText { |marking|
        if (marking.kind == \text) { ^marking.value.asCompileString };
        if (marking.kind == \sforzando) {
            ^Marking.sforzandoSpelling(marking.value)
        };
        ^marking.value.asString
    }

    // A row has space for the kind and the placement the summary drops.
    *prMarkingSidecarText { |marking|
        var text = marking.kind.asString ++ " " ++ this.prMarkingText(marking);
        marking.placement !? { text = text ++ " " ++ marking.placement };
        ^text
    }

    // Endpoint kind/edge/id print even for dangling endpoints. Validation is elsewhere.
    //
    // >>> RastrumTreeDisplay.prSpannerText(Spanner.slurStop(2))   -> slur:stop#2
    *prSpannerText { |spanner|
        var text = spanner.kind.asString ++ ":" ++ spanner.edge
            ++ "#" ++ spanner.id;
        spanner.direction !? { text = text ++ " " ++ spanner.direction };
        spanner.text !? {
            text = text ++ " " ++ spanner.text.asCompileString
                ++ " " ++ spanner.placement
        };
        ^text
    }

    // Tempo ramps print like endpoints.
    //
    // Point directions print their payload.
    //
    // >>> RastrumTreeDisplay.prDirectionText(Direction.rehearsalMark("A"))
    // rehearsalMark "A"
    // >>> RastrumTreeDisplay.prDirectionText(Direction.tempoRampStop(0, 2))
    // tempoRamp:stop#2
    // >>> RastrumTreeDisplay.prDirectionText(Direction.metronome("4", 96))
    // tempo 1/4=96
    *prDirectionText { |direction|
        var text = direction.kind.asString;
        direction.edge !? { text = text ++ ":" ++ direction.edge };
        direction.id !? { text = text ++ "#" ++ direction.id };
        if (direction.hasText) {
            text = text ++ " " ++ direction.text.asCompileString
        };
        direction.unit !? {
            text = text ++ " " ++ this.prTime(direction.unit)
                ++ "=" ++ direction.perMinute
        };
        if (direction.atBarStart.not) {
            text = text ++ " at " ++ this.prTime(direction.offset)
        };
        ^text
    }

    // A grace leaf is a leaf. It is labeled as one. Its own marks are
    // summarized rather than given rows of their own.
    *prGraceText { |leaf, settings|
        ^this.prBody(leaf, nil, settings) ++ this.prSummaryOf(leaf, settings)
    }

    // Show sounding time only where it differs from written time.
    *prTimes { |element|
        var written = element.duration;
        var sounding = element.prolatedDuration;
        if (sounding != written) {
            ^" " ++ this.prTime(written) ++ "=>" ++ this.prTime(sounding)
        };
        ^" " ++ this.prTime(written)
    }

    // >>> RastrumTreeDisplay.prTime(Duration(2, 2))   -> 1
    // >>> RastrumTreeDisplay.prTime(Duration(3, 8))   -> 3/8
    *prTime { |duration|
        if (duration.denominator == 1) { ^duration.numerator.asString };
        ^"" ++ duration.numerator ++ "/" ++ duration.denominator
    }

    // Keep residual cents visible on the pitch they belong to.
    //
    // >>> RastrumTreeDisplay.prPitchText(MusicPitch("eb[3]"))   -> eb[3]
    *prPitchText { |pitch|
        if (pitch.cents == 0) { ^pitch.spelling };
        ^pitch.spelling ++ "(" ++ pitch.cents ++ " cents)"
    }
}


+ ScoreElement {
    // Paths are relative to the receiver.
    //
    // >>> Measure("2/4", "c4 r4").treeString.contains("[1] Rest")   -> true
    treeString { |options| ^RastrumTreeDisplay.string(this, options) }

    // Posts `treeString` and answers the receiver.
    postTree { |options| ^RastrumTreeDisplay.post(this, options) }
}


+ ScoreSelection {
    // The source tree with held rows marked.
    //
    // >>> { var bar = Measure("2/4", "c4 d4");
    //     ScoreSelection(bar).atIndices([0]).treeString.contains("selected") }
    //     .value   -> true
    treeString { |options| ^RastrumTreeDisplay.selectionString(this, options) }

    // Posts `treeString` and answers the selection.
    postTree { |options| ^RastrumTreeDisplay.selectionPost(this, options) }
}


+ RhythmCell {
    // Cell paths go to `shareAt`. Divide rows also go to `cellAt`.
    //
    // >>> RhythmCell("(1 -3 (2 (1 -1 1)) 1)").treeString.contains("[2] divide 2")   -> true
    treeString { |options| ^RastrumTreeDisplay.string(this, options) }

    // Posts `treeString` and answers the receiver.
    postTree { |options| ^RastrumTreeDisplay.post(this, options) }
}
