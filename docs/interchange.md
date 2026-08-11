# ScoreJSON interchange

ScoreJSON is not a standard: it exists so that Rastrum and metasonic-score — an
unreleased side project doing the same job in Haskell — can exchange whole
scores. This is the schema, its version history, and how MusicXML divisions are
chosen.

## Interchange with metasonic-score

`ScoreJSONWriter` and `ScoreJSONReader` are the two ends of that contract: a
tagged tree with a `"type"` discriminator, exact rationals as
`[numerator, denominator]` pairs, and no floats on the wire except `cents`,
which is a deviation rather than a duration.

```json
{"format":"rastrum-score","version":18,"type":"score","title":"Study I",
 "staves":[{"type":"staff","name":"Violin","clef":"treble","measures":[
   {"type":"measure","meter":[4,4],"elements":[
     {"type":"tuplet","multiplier":[2,3],"elements":[
       {"type":"note","pitch":{"step":0,"alter":[0,1],"octave":4,"cents":0.0},
        "duration":[1,2],"tiesToNext":true},
       {"type":"note","pitch":{"step":0,"alter":[0,1],"octave":4,"cents":0.0},
        "duration":[1,2]}]}]}]}]}
```

A tie needs somewhere to land, so the example carries both halves: the first note
ties forward and the second continues the same pitch. A tie with no compatible
leaf after it is a dangling tie, and `MusicXMLWriter` throws on one rather than
opening a tie it never closes.

### Current format: version 18


#### Document rules

- A document is one JSON object for a whole score. Fragments are not documents.
- The envelope is required: `"format":"rastrum-score"`,
  `"version":18`, and `"type":"score"`.
- Every tree node carries a `"type"` discriminator.
- Readers accept exactly one version. Older and newer versions are refused.
- Required fields must be present. Unknown fields and fields on the wrong node
  are refused.
- Writers omit absent, empty and default facts rather than writing `null`.
- Array order is semantic: staves, measures, elements, markings, spanners,
  directions, pitches and graces read in the order written.
- Durations, offsets, tuplets and alterations use exact rational pairs. The only
  float field is `cents`.

#### Scalar encodings

| Name             | JSON shape                | Contract                                           |
| ---------------- | ------------------------- | -------------------------------------------------- |
| rational         | `[numerator,denominator]` | both whole numbers; denominator > 0                |
| integer          | JSON number               | whole number; no decimal spelling                  |
| positive integer | JSON number               | integer > 0                                        |
| string           | JSON string               | escaped JSON text; no raw control characters       |
| boolean          | `true` or `false`         | no alternate spellings                             |
| duration         | rational                  | written time; ordinary leaves may need preparation |
| grace duration   | rational                  | must be one notatable note value                   |
| pitch            | object                    | `step`, `alter`, `octave`, optional `cents`        |

Pitch is:

| Field    | Contract                                                 |
| -------- | -------------------------------------------------------- |
| `step`   | integer 0..6, for C D E F G A B                          |
| `alter`  | rational semitone offset on the quarter-tone grid, -2..2 |
| `octave` | integer, scientific pitch notation                       |
| `cents`  | plain decimal number, absent means `0`; writer emits it  |

#### Tree nodes

Each requires its own `"type"` value:

| Type        | Required fields               | Optional fields                                                      |
| ----------- | ----------------------------- | -------------------------------------------------------------------- |
| `score`     | `format`, `version`, `staves` | `title`, `composer`                                                  |
| `staff`     | `measures`                    | `name`, `clef`                                                       |
| `measure`   | `meter`, `elements`           | `meterGrouping`, `clef`, `directions`, `barDuration`, `metricOffset` |
| `voice`     | `elements`                    | `name`                                                               |
| `container` | `elements`                    |                                                                      |
| `tuplet`    | `multiplier`, `elements`      | `actualNotes`, `normalNotes`                                         |
| `note`      | `pitch`, `duration`           | `tiesToNext`, `markings`, `spanners`, `graces`, `graceStyle`         |
| `rest`      | `duration`                    | `markings`, `spanners`, `graces`, `graceStyle`                       |
| `chord`     | `pitches`, `duration`         | `tiesToNext`, `markings`, `spanners`, `graces`, `graceStyle`         |

Child fields are typed by position: `score.staves` holds `staff` nodes,
`staff.measures` holds `measure` nodes, and every `elements` array holds score
elements: `voice`, `container`, `tuplet`, `note`, `rest`, or `chord`.
`graces` is separate and holds only `graceNote` and `graceChord`.

#### Node fields

`meter` is `[count,unit]`, with both values positive integers. `meterGrouping`
is a non-empty list of positive integers whose sum is `count`; absent means
undivided.

`clef` is one of `alto`, `bass`, `percussion`, `tenor`, or `treble`. On a staff
it is the opening clef; on a measure it is a clef change at that bar.

`barDuration` and `metricOffset` are rational pairs and must appear together or
not at all. Together they define a partial bar: the span and where that span
begins inside the notional meter.

`multiplier` is the tuplet time multiplier as a rational. `actualNotes` and
`normalNotes` must appear together or not at all; when present they are positive
integers and must reduce to the multiplier beside them.

`tiesToNext` is omitted when false. On a note it is a Boolean. On a chord it is
one Boolean per pitch; a whole-chord tie is an all-true mask, not `true`.

#### Records

| Record       | Required fields               | Optional fields                       |
| ------------ | ----------------------------- | ------------------------------------- |
| `pitch`      | `step`, `alter`, `octave`     | `cents`                               |
| `marking`    | `type`, `value`               | `placement`                           |
| `spanner`    | `type`, `edge`, `id`          | `direction`, `text`, `placement`      |
| `direction`  | `type`                        | `text`, `offset`, `unit`, `perMinute` |
| `graceNote`  | `type`, `pitch`, `duration`   |                                       |
| `graceChord` | `type`, `pitches`, `duration` |                                       |

Marking types are `dynamic`, `articulation`, and `text`. Dynamic values are
`pppp`, `ppp`, `pp`, `p`, `mp`, `mf`, `f`, `ff`, `fff`, `ffff`.
Articulation values are `staccato`, `staccatissimo`, `tenuto`, `accent`, and
`marcato`. A text marking's `value` is prose and its `placement` is required;
non-text markings refuse `placement`. Placements are `above` and `below`.

Spanner types are `slur`, `hairpin`, `text`, and `beam`; edges are `start` and
`stop`; `id` is a positive integer. A hairpin start requires `direction`,
`crescendo` or `diminuendo`. A text start requires `text` and `placement`.
Stops, slurs and beams carry no prose or direction.

Direction types are `tempo`, `rehearsalMark`, and `text`. `offset` is a
rational into the bar and defaults to zero. A direction needs `text`, except a
tempo may instead carry a metronome mark. A metronome mark is `unit` plus
`perMinute`, both or neither; `unit` is one notatable note value and `perMinute`
is a positive integer.

Grace groups sit on a leaf. `graces` must be a non-empty list when present.
`graceStyle` is `grace` or `acciaccatura`; absent means `grace`. A grace leaf is
only `graceNote` or `graceChord`, with pitch data and a grace duration. It
carries no ties, markings, spanners, or nested grace group.

#### Validation boundary

`ScoreJSONReader` checks the document shape and rebuilds a tree. It does not
decide whether the score is complete notation. `Rastrum.readJSON` also runs
`Validator`, so it catches score facts such as a short undeclared bar, a
dangling tie, an unpaired spanner, or a direction outside its bar.

`Rastrum.writeJSON` does not add automatic beams. With its default
`prepare:true`, it writes the prepared tree; with `prepare:false`, it writes the
given tree. A beam in the document is a beam the score already carried.
