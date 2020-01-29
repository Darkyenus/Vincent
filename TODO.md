# Implement
- Make the participant code sequential
- `<section min-time`
- `<section stage`
- `<section shown-wine`
- Starting the questionnaire without any wines
- Color per questionnaire wine code (palette of at least 10 colors)
- Collect demographic information for registered panelists
    - Name
    - Email (already collected)
    - Mobile phone (optional)
    - Data (= date of registration?)
    - Gender (Female|Male|Other)
    - Year of birth
    - Home country/region
    - Highest achieved education:
        - None
        - Primary (ISCED 1, Scuola Elementare)
        - Secondary (ISCED 2 or 3, Scuola Media/Superiore)
        - Post-secondary (ISCED 4 or 5)
        - Bachelor or equivalent (ISCED 6, Laurea)
        - Master or equivalent (ISCED 7, Laurea magistrale)
        - Doctoral or equivalent (ISCED 8)
        - Higher
    - Do you smoke? (If so, how many times per day?)
    - Do you have any food intolerance? (If so, which?)
    - Do you have [sulfite intolerance](https://en.wikipedia.org/wiki/Sulfite#Health_effects)?
- Display gender and intolerances in the questionnaire's participant list 
- Allow questionnaire to start without any wines
- Allow to invite everyone from some other questionnaire, creating a dependency
- Implement guest accounts, with separate codes (hmm)
    - Something like #G341234
- Implement "Registered users list"
    - Show all collected info to administrators
    - Show only Name, Email, Phone, Code and intolerances to regular staff
        - Is this ok? Ask!