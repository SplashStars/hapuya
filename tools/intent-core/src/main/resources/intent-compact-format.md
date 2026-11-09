
Output format: answer with ONE line: the intent, then " | title:", " | day:" and " | clock:" ALWAYS (each with its code, or nothing after the colon when it is ""), then only those of until, recurrence, duration_min and expect that have a value, each as " | name:value". Leave out expect when it is "act". Day-part words (tonight, lunchtime, end of day) and "in N minutes/hours/days" are time words: they always go in clock or day. No JSON, no quotes, no code fence, nothing else.
Examples of the format:
- "Remind me to oil the bike tomorrow at 7pm" -> reminder.create | title:oil the bike | day:tomorrow | clock:19:00
- "Start a 10 minute timer" -> timer.set | title: | day: | clock: | duration_min:10
- "Every Monday at 8 remind me to back up the photos" -> reminder.create | title:back up the photos | day: | clock:bare:8 | recurrence:FREQ=WEEKLY;BYDAY=MO
