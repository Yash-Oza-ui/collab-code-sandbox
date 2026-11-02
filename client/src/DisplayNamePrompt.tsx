import { useState } from 'react'
import type { FormEvent } from 'react'
import { MAX_DISPLAY_NAME_LENGTH, validateDisplayName } from './displayName'

export interface DisplayNamePromptProps {
  onSubmit: (displayName: string) => void
}

export default function DisplayNamePrompt({ onSubmit }: DisplayNamePromptProps) {
  const [error, setError] = useState<string | null>(null)

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const formData = new FormData(event.currentTarget)
    const result = validateDisplayName(formData.get('displayName'))

    if (!result.valid) {
      setError(result.message)
      return
    }

    setError(null)
    onSubmit(result.displayName)
  }

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
            autoComplete="nickname"
            autoFocus
            aria-describedby={error ? 'display-name-error' : 'display-name-help'}
          />
          {error ? (
            <p className="display-name-error" id="display-name-error" role="alert">
              {error}
            </p>
          ) : (
            <p className="display-name-help" id="display-name-help">
              Up to {MAX_DISPLAY_NAME_LENGTH} characters.
            </p>
          )}
          <button type="submit">Join room</button>
        </form>
      </section>
    </main>
  )
}
