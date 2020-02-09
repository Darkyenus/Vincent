# Questionnaire template files
Questionnaire template files define the structure of a questionnaire run.
Specifically, they define the questions that the questionnaire consists of,
their types, machine-readable names, user presentation, etc.

They do not define what is the questionnaire about - for example the wine about which the questionnaire is,
is not defined by these files.

Questionnaire files are defined in [XML](https://en.wikipedia.org/wiki/XML), which is a widely used markup language.
They have the `.xml` file extension.

Partial [DTD](https://en.wikipedia.org/wiki/Document_type_definition) specification of the template file format is available [here](/questionnaire.dtd).

## Quick XML overview
This section describes main aspects of XML which are required to understand before editing and creating questionnaire
template files. If you are already familiar with XML, you may skip this section.

XML is a machine-readable format, which means that it has a somewhat rigid structure whose rules must be obeyed.
The whole document consists of *elements*, which may be arbitrarily nested (although some rules apply). Elements
are denoted by a pair of tags:
```xml
<section> </section>
```
The type of this element is `section`, `<section>` is the opening tag and `</section>` is the closing tag.

Nesting is allowed, but element may not end before any element that started before it:
```xml
<section> <part1> </part1> <part2> </part2> </section>
```
When an element is nested into a different element, such as elements `part1` and `part2` are nested into `section`,
it is said that the `section` is a parent of elements `part1` and `part2`.

Only one element in the whole document can have no parents - the root element.

The whitespace (space, tab and newline characters) around tags is usually not significant.
Some elements may also contain arbitrary text:
```xml
<option>Yellow</option>
```
Note that since `<` characters signifies a start of a next tag, you may not directly use it in your text.
Similar restrictions apply to a few other characters. Use the replacements from the following table if you want to write them.

| If you want to write | Write this instead |
|:--------------------:|:------------------:|
| <                    | `&lt;`             |
| >                    | `&gt;`             |
| &                    | `&amp;`            |
| "                    | `&quot;`           |
| '                    | `&apos;`           |

See [here](https://en.wikipedia.org/wiki/List_of_XML_and_HTML_character_entity_references) for a full list of XML/HTML entities.

If you want to keep some notes or comments in the XML document which should not be processed by Vincent,
wrap your text in comment tags, like so:
```xml
<!-- This is a comment -->
```
Comment may contain any text, except for `-->`, because that ends the comment.

XML elements may also contain some attributes, which appear in the opening tag, after the element name:
```xml
<section amount="10" different-attribute='hello!'> </section>
```
The attribute's value must always be surrounded by either single `'` or double `"` quotation marks. It does not matter
which one you pick, but the opening and closing mark must be the same, and the text within must not contain that symbol.
If you want to write it, use the replacement from the table above.

Many attributes have default values, i.e. a value that the computer will use for that attribute if you do not specify any (=do not mention the attribute at all).

### Writing and editing XML
While there are specialized XML editors, you may edit XML in any plain text file editor.
Here are some recommended free editors by operating system:
- Microsoft Windows: [Notepad++](https://notepad-plus-plus.org/). The built-in `notepad.exe` is not recommended. Editing XML in [WordPad](https://en.wikipedia.org/wiki/WordPad) or [Microsoft Word](https://en.wikipedia.org/wiki/Microsoft_Word) **is not possible**. 
- Apple macOS: [BBEdit](https://www.barebones.com/products/bbedit/) (while a paid version exists, free mode is sufficient) The built-in [TextEdit](https://en.wikipedia.org/wiki/TextEdit) is not recommended.
- Linux: You may use any of the built-in command line editors ([nano](https://www.nano-editor.org/), [vim](https://www.vim.org/), [emacs](https://www.gnu.org/software/emacs/)), or any GUI editor, such as [Gedit](https://wiki.gnome.org/Apps/Gedit) or any other plain text editor that is pre-installed on your system.

When editing the XML files for Vincent, use [UTF-8 encoding](https://en.wikipedia.org/wiki/UTF-8).
The recommended text editors should do so by default. UNIX-style line endings (*LF*) are recommended, but either should work.

## General template file structure
Minimal questionnaire template file will look as follows:
```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<!DOCTYPE questionnaire PUBLIC "-//UNIBZ//Vincent Questionnaire Template 1.0//EN" "vincent://questionnaire.dtd">
<questionnaire>
    <title>Example Questionnaire</title>
    <section>
        <info>
            <text>This is a simple questionnaire without any questions.</text>
        </info>
    </section>
</questionnaire>
```
The first two lines are mandated by the XML standard. Do not change them.
On the third line, the `<questionnaire>` root element starts.

What follows is the titles of the questionnaire and its first and only section. The section only contains informational text.

## Template file elements
This section goes over each element that may appear in the template file in detail.

### Language format
When an attribute calls for a language specification, the format is an [ISO 639-1](https://en.wikipedia.org/wiki/List_of_ISO_639-1_codes) code
optionally followed by a hyphen-separated [ISO 3166-1](https://en.wikipedia.org/wiki/ISO_3166-1) alpha 2 code.
For example: `it`, `en-US`, `cs-CZ`.

A [boolean value](https://en.wikipedia.org/wiki/Boolean_data_type) may only be `true` or `false`.

[HTML](https://en.wikipedia.org/wiki/HTML) formatted text refers to a normal text, which may contain HTML formatting elements,
which work very similarly to XML elements (and look the same as well), but denote how the text will look, among other things.
For example `Some <b>text</b> with <i>tags</i>` will appear to the questionnaire participants as "Some **text** with *tags*".
There are many HTML tags and there is a large amount of web resources which describe them. Vincent does not limit which tags
may use, but note that some tags may negatively impact the visual design of the questionnaire presentation, its functionality,
or in rare cases even its security. When in doubt, consult with any good web developer.

### \<questionnaire>
The root element.

- Attributes:
    - `default-lang`: All [`title`](#title) elements that do not have explicit [language](#language-format) set, will use this one. This [language](#language-format) is also used by default when user does not request any compatible [language](#language-format). The default value is `en` for english.
- Content: Nested elements
    - [`title`](#title) at least one. Using HTML tags in these titles is discouraged and not supported.
    - [`section`](#section) at least one

### \<title>
Used as a title of the whole questionnaire, sections, and individual questions.
Multiple titles can always appear after each other, as long as their languages are different.
Only one title will be shown in that case - the one with the appropriate language (but see the `always` attribute for exception).

- Attributes:
    - `lang`: [Language](#language-format) of this title element.
    - `always`: Boolean value. If true, this title will be visible even if a more suitable language mutation has been found.
- Content: HTML formatted text

### \<section>
Set of questions on one page.
After all required questions are answered, user can move to the next section.
It is not possible to move back to previous sections.

Some sections may be only shown for some wine runs (=*stages*).
Any questions in the omitted sections will be presented as if they were optional and were left unanswered, i.e. the column value will be blank.
The intended purpose for this feature are "welcome sections" (only-first), "goodbye sections" (only-last), and "wait and cleanse palate sections" (except-first).

- Attributes:
    - `min-time`: Specifies minimum time limit for the section. Time duration is in seconds. Participant will not be allowed to continue to the next section unless a given amount of seconds has elapsed since opening this section. Other requirements to continue still apply.
    - `stage`: Specifies the stage (run) in which this section will be shown in relation to the amount of tested wines.
        - `only-first` section will be shown only for the first wine of the questionnaire and will be omitted for subsequent runs through the template stages.
        - `except-first` will be shown on each questionnaire run, except fo the one with the first assigned wine.
        - `only-last` will be shown only for the run of the last wine
        - `except-first` will be shown for all runs, except for the one of the first wine
        - `always` **default** will be shown on all runs
    - `shown-wine`: Governs which wine code will be shown to the participant. This can be used to show a full overview of tested wines in welcome or wait sections (all), and to suppress redundant wine code display in goodbye sections (none). The "current" shows only the code of the wine tested in this run. If the questionnaire does not pertain to any wine (was started without any wines assigned), no wine codes will be shown even if this attribute demands it.
        - `current`: **default** show the current wine code at the top of the page
        - `none`: Don't show any wine codes
        - `all`: Show all wine codes at the top of the page
- Content: Nested elements
    - [`title`](#title): Zero or more
    - [`info`](#info) and [`question`](#question): zero or more, in any order

### \<info>
Visually similar to a [`question`](#question) element, but only presents some textual information.
It may start with a [`title`](#title) and then it may contain [`text`](#text).

- Content: Nested elements
    - [`title`](#title): zero or more
    - [`text`](#text): zero or more

### \<text>
Works similarly to [`title`](#title) with regard to localization, but presents a body of text instead of just the title.

- Attributes:
    - `lang`: [Language](#language-format) of the text
- Content: HTML formatted text

### \<question>
Defines a single question that will appear in the resulting CSV.

- Attributes:
    - `id`: **mandatory** Specifies how will the result appear in the resulting CSV (=column name). (NOTE: Some question types may not use the ID directly, but may derive multiple real IDs from it, for example [`time-progression`](#time-progression)). Total length of the generated question ID MUST not exceed 64 characters.
    - `required`: Boolean, default is `true`, which means that the question must have a response before moving on to the next section.
- Content: Nested elements
    - [`title`](#title): Zero or more
    - [`text`](#text): Zero or more
    - [`one-of`](#one-of), [`free-text`](#free-text), [`scale`](#scale) or [`time-progression`](#time-progression): Exactly one

### \<one-of>
This question type allows user to select one of the available options.
Option is specified by [`title`](#title) text.

Options can be grouped by their category - those categories can have own titles.
Note that still only one option from one category can be selected.

Each option must have a distinct `value` attribute. This value will be shown in the resulting CSV (=cell value).

- Content: Nested elements
    - either [`category`](#category) or [`option`](#option): One or more. Using [`option`](#option) elements is equivalent to using a single [`category`](#category) with these [`option`](#option)s and serves as a shorthand.

### \<category>
A category of options for [`one-of`](#one-of). May have its own title.

- Content: Nested elements
    - [`title`](#title): Zero or more
    - [`option`](#option): One or more

### \<option>
A single option that can be chosen in the [`one-of`](#one-of) question type.

- Attributes:
    - `value`: Specifies how will this option appear in the resulting CSV. If it is omitted, then the full text of the default title is used. It is an error to specify two options with the same value in one question.
    - `detail`: Boolean value signifying if user can specify additional free-text detail when picking this option. The detail text will appear in a column with the same ID as the question, with "-detail-<value>" appended. For example: "condition", "condition-detail-faults".
    - `detail-type`: One of [`free-text`](#free-text) types.
- Content: Either HTML formatted text, which is a shorthand to the same text wrapped in a [`title`](#title) or nested elements
    - [`title`](#title): One or more - the text that is displayed to the user when picking this option
    - [`detail`](#detail): Zero or more - the prompt name for the detail field, if enabled

### \<detail>
Works identically to [`text`](#text).

- Attributes:
    - `lang`: [Language](#language-format) of the text
- Content: HTML formatted text

### \<free-text>
Question type that allows user to input arbitrary text, possibly with some hints on what the text should be.

- Attributes:
    - `type`:
        - `sentence`: **(default)** single line of text is expected
        - `paragraph`: a more substantial amount of text is expected
        - `number`: a number is expected ("real" numbers are allowed, including negatives and non-whole numbers, but some browsers may not support writing them)
- Content: Nested elements
    - [`placeholder`](#placeholder): Zero or more - the text shown in dimmed styling when no input is entered, but is not submitted. Used to hint on what kind of value this field expects. Beware of introducing question biases through this hint.

### \<placeholder>
Works similarly to [`text`](#text).

- Attributes:
    - `lang`: [Language](#language-format) of the text
- Content: text without HTML tags

### \<scale>
Similar to [`one-of`](#one-of), only one option can be chosen, but the options are numbers in the range
given by `min` and `max` attributes. Both ends of the range are inclusive.

Ends of the scale can optionally be titled using [`min`](#min-and-max) and [`max`](#min-and-max) tags.

- Attributes
    - `min`: Whole number. Low end of the scale, inclusive. **1** by default.
    - `max`: Whole number. High end of the scale, inclusive. **7** by default.
- Content: Nested elements
    - [`min`](#min-and-max): Zero or one. The label for the low end of the scale.
    - [`max`](#min-and-max): Zero or one. The label for the high end of the scale.

### \<min> and \<max>
Works similarly to [`option`](#option) in that it either accepts text directly or wrapped in [`title`](#title),
when more languages than default are required.

- Content: Either HTML formatted text, which is a shorthand to the same text wrapped in a [`title`](#title) or nested elements
    - [`title`](#title): One or more - the text that is displayed to the user when picking this option

### \<time-progression>
Container that presents the same question repeatedly after given time intervals.
Timer starts after an explicit start and then presents the same question again repeatedly in fixed intervals.
If user does not manage to respond in time, value is left blank.

A `require="true"` on this question type does not mean that all repeats of the progression must be answered, but that the
time progression must show all repeats. The question presented inside is not mandatory, to prevent skewing the results when
a respondent does not manage to answer in time.

In resulting CSV, the values will be in columns labelled `N-ID`, where ID is the question ID and N is the repeat.
For example for `<question id="taste"> <time-progression repeats="3">`, those columns will be created: "0-taste", "1-taste", "2-taste".

- Attributes:
    - `interval`: Whole positive number of how many seconds there should be between questions. Values smaller than 5 seconds may be difficult to respond properly for some users.
    - `repeats`: Whole positive number. How many times should the question be shown.
- Content: One nested element
    - Either [`one-of`](#one-of) or [`scale`](#scale)

## Full examples
As a starting point for your own templates, feel free to use one of these example templates.

- Simple template showcasing most question types and localization support - <a href="/docs/questionnaire-example.xml" download>**Download**</a>
- Showcase of the [`time-progression`](#time-progression) question type - <a href="/docs/questionnaire-example-time-progression.xml" download>**Download**</a>





<!-- This file is converted to HTML using pandoc: `pandoc -f gfm -t html QuestionnaireDesign.md -o QuestionnaireDesign.html` -->