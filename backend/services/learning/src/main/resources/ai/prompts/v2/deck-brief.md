---
section: deck-brief
prompt_version: v2
purpose: "Deck brief (L3-L5): title, description, profile, deck terms, exemplars with a style card, the most recent material and the outline. Stable within a session, so it sits in the cacheable prefix after the global core."
---
<deck>
<title>{{deck.title}}</title>
<description>{{deck.description}}</description>
<output_language>{{lang.output}}</output_language>
<profile>изучаемый язык или область: {{lang.target}}; уровень: {{level|"не указан"}};
материалов: {{counts.items}}; упражнений: {{counts.exercises}}</profile>
<terms>{{deck_terms}}</terms>
</deck>
<exemplars>
Это образцы автора. Возьми из них длину, тон, порядок частей, плотность разметки и способ
подачи примеров. Не бери темы, факты, примеры и фразы.
<style_card>{{style_card.words}} слов; подзаголовков {{style_card.headings}}; списков {{style_card.lists}};
таблиц {{style_card.tables}}; примеров {{style_card.examples}}; аудио {{style_card.audio}}</style_card>
{{exemplar_blocks}}
</exemplars>
<recent_material id="R1">Последний материал колоды — для согласования терминов и
продолжения темы.
{{recent_material}}</recent_material>
<outline total="{{outline.total}}" shown="{{outline.shown}}">
{{outline.lines}}
</outline>
