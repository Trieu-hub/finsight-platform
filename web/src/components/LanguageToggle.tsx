import { useI18n, type Lang } from '../i18n'

// Small EN/VI segmented switch for the header.
export default function LanguageToggle() {
  const { lang, setLang, t } = useI18n()
  const opts: Lang[] = ['en', 'vi']
  return (
    <div
      role="group"
      aria-label={t('lang.label')}
      className="flex items-center rounded-lg border border-neutral-800 p-0.5"
    >
      {opts.map((l) => (
        <button
          key={l}
          onClick={() => setLang(l)}
          aria-pressed={lang === l}
          className={`rounded-md px-2 py-1 text-xs font-semibold transition ${
            lang === l
              ? 'bg-neutral-800 text-neutral-100'
              : 'text-neutral-500 hover:text-neutral-300'
          }`}
        >
          {t(`lang.${l}`)}
        </button>
      ))}
    </div>
  )
}
