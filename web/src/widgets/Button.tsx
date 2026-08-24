import type { ButtonHTMLAttributes } from 'react'

import styles from './Button.module.css'

type Variant = 'primary' | 'secondary' | 'quiet' | 'danger'

interface ButtonStyleOptions {
  variant?: Variant
  wide?: boolean
  small?: boolean
  className?: string
}

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant
  wide?: boolean
  small?: boolean
}

export function Button({
  variant = 'secondary',
  wide,
  small,
  className,
  type = 'button',
  ...rest
}: ButtonProps) {
  return <button type={type} className={buttonClassName({ variant, wide, small, className })} {...rest} />
}

/** То же оформление для навигационной ссылки без вложенного `<button>`. */
export function buttonClassName({
  variant = 'secondary',
  wide,
  small,
  className,
}: ButtonStyleOptions = {}): string {
  const classes = [
    styles.button,
    styles[variant],
    wide ? styles.wide : '',
    small ? styles.small : '',
    className ?? '',
  ]
    .filter(Boolean)
    .join(' ')

  return classes
}
