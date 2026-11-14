import { useState } from 'react'
import type { FormEvent } from 'react'
import { MAX_DISPLAY_NAME_LENGTH, validateDisplayName } from './displayName'

export interface DisplayNamePromptProps {
  onSubmit: (displayName: string) => void
  serverError?: string | null
  submitting?: boolean
  initialValue?: string
}

export default function DisplayNamePrompt({
  onSubmit,
  serverError = null,
  submitting = false,
  initialValue = '',
}: DisplayNamePromptProps) {
  const [error, setError] = useState<string | null>(null)

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting) return

    const formData = new FormData(event.currentTarget)
    const result = validateDisplayName(formData.get('displayName'))

    if (!result.valid) {
      setError(result.message)
      return
    }

    setError(null)
    onSubmit(result.displayName)
  }

  const activeError = error ?? serverError

  return (
    <main className="display-name-screen">
      <section className="display-name-card" aria-labelledby="display-name-title">
        <h1 id="display-name-title">Choose your display name</h1>
        <p>Enter a name your teammates can recognise in this room.</p>
        <form onSubmit={handleSubmit} noValidate>
          <label htmlFor="display-name">Display name</label>
          <input
            id="display-name"
            name="displayName"
            type="text"
            maxLength={MAX_DISPLAY_NAME_LENGTH}
            defaultValue={initialValue}
            autoComplete="nickname"
            autoFocus
            disabled={submitting}
            onChange={() => {
              if (error) setError(null)
            }}
            aria-describedby={activeError ? 'display-name-error' : 'display-name-help'}
          />
          {activeError ? (
            <p className="display-name-error" id="display-name-error" role="alert">
              {activeError}
            </p>
          ) : (
            <p className="display-name-help" id="display-name-help">
              Up to {MAX_DISPLAY_NAME_LENGTH} characters.
            </p>
          )}
          <button type="submit" disabled={submitting}>
            {submitting ? 'Joining room...' : 'Join room'}
          </button>
        </form>
      </section>
    </main>
  )
}
