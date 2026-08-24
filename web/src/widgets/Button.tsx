import type { ButtonHTMLAttributes } from 'react'

import styles from './Button.module.css'

type Variant = 'primary' | 'secondary' | 'quiet' | 'danger'

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
  const classes = [
    styles.button,
    styles[variant],
    wide ? styles.wide : '',
    small ? styles.small : '',
    className ?? '',
  ]
    .filter(Boolean)
    .join(' ')

  return <button type={type} className={classes} {...rest} />
}
