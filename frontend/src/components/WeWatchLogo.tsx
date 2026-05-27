interface Props {
  onDark?: boolean
  height?: number
}

function WeWatchLogo({ onDark = false, height = 56 }: Props) {
  const weColor = onDark ? '#f8fafc' : '#071A3D'
  const watchColor = '#22B6B0'
  const width = Math.round(height * (520 / 140))

  return (
    <svg
      width={width}
      height={height}
      viewBox="0 0 520 140"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      role="img"
      aria-label="WeWatch"
      style={{ display: 'block' }}
    >
      <text
        x="40"
        y="74"
        fontFamily='"Nunito", "Avenir Next Rounded", "Arial Rounded MT Bold", "Inter", system-ui, sans-serif'
        fontSize="96"
        fontWeight="800"
        letterSpacing="-4"
        dominantBaseline="middle"
      >
        <tspan fill={weColor}>we</tspan>
        <tspan fill={watchColor}>watch</tspan>
      </text>
    </svg>
  )
}

export default WeWatchLogo
