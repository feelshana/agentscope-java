import React from 'react';

/**
 * Static "example question" cards shown under the landing composer, mirroring the
 * TC-DataAgent hero layout. Purely presentational: no data fetching and no click
 * behaviour yet (backend sample-dataset wiring is a separate follow-up).
 */

/** Small four-point sparkle that prefixes each example title. */
function SparkIcon() {
  return (
    <svg className="da-example-spark" viewBox="0 0 12 12" width="12" height="12" aria-hidden="true">
      <path
        d="M6 0.6 L7.15 4.15 L10.7 5.3 L7.15 6.45 L6 10 L4.85 6.45 L1.3 5.3 L4.85 4.15 Z"
        fill="currentColor"
      />
    </svg>
  );
}

/** Decorative forecast line chart (solid history + dashed projection). */
function TrendThumb() {
  return (
    <svg className="da-example-thumb" viewBox="0 0 227 120" preserveAspectRatio="none" aria-hidden="true">
      <g fill="none">
        {[0.125, 27.5, 54.875, 82.25, 109.625].map(y => (
          <rect key={y} x="0.125" y={y} width="226.417" height="0.25" stroke="rgb(233,236,241)" strokeWidth="0.25" />
        ))}
        <path
          d="M11.6091 33.091C11.6091 33.091 28.5861 15.9251 37.0985 21.5617C45.6109 27.1982 50.3797 57.1744 62.5879 57.1744C74.7961 57.1744 76.6321 38.2151 88.0773 38.2151C99.5225 38.2151 98.1157 98.6798 113.567 98.936"
          stroke="rgb(0,82,217)"
          strokeWidth="1.5"
        />
        <path
          d="M113.348 98.9353C128.766 99.1914 123.555 33.3724 138.783 38.2384C154.01 43.1044 152.987 15.9573 164.217 15.9573C175.448 15.9573 180.706 32.348 189.652 30.0431C198.598 27.7381 215.087 36.1896 215.087 36.1896"
          stroke="rgb(0,82,217)"
          strokeWidth="1.5"
          strokeDasharray="2 2"
        />
      </g>
    </svg>
  );
}

/** Decorative donut chart with a real-text centre label. */
function DonutThumb() {
  return (
    <div className="da-example-donut">
      <svg viewBox="0 0 114 114" aria-hidden="true">
        <g stroke="white" strokeWidth="0.470588">
          <path
            d="M57 1C68.826 1 80.3485 4.74388 89.916 11.695C99.4834 18.6462 106.605 28.4478 110.259 39.6951C113.914 50.9423 113.914 63.0577 110.259 74.305C106.605 85.5522 99.4834 95.3538 89.916 102.305L80.0412 88.7135C86.7384 83.8476 91.7233 76.9865 94.2814 69.1135C96.8395 61.2404 96.8395 52.7596 94.2814 44.8865C91.7233 37.0135 86.7384 30.1524 80.0412 25.2865C73.344 20.4207 65.2782 17.8 57 17.8L57 1Z"
            fill="rgb(105,158,245)"
          />
          <path
            d="M89.873 102.336C77.8491 111.055 62.8543 114.64 48.1872 112.302C33.5202 109.965 20.3823 101.897 11.6638 89.873L25.2647 80.0111C31.3676 88.4278 40.5641 94.0754 50.831 95.7115C61.098 97.3477 71.5944 94.8382 80.0111 88.7353L89.873 102.336Z"
            fill="rgb(187,211,251)"
          />
          <path
            d="M11.695 89.916C4.39008 79.8615 0.637256 67.6628 1.02763 55.241C1.41801 42.8192 5.92941 30.8801 13.8513 21.3042L26.7959 32.013C21.2506 38.7161 18.0926 47.0734 17.8193 55.7687C17.5461 64.464 20.1731 73.0031 25.2865 80.0412L11.695 89.916Z"
            fill="rgb(227,236,255)"
          />
          <path
            d="M14.1015 21.0039C19.4002 14.6892 26.0276 9.62211 33.5107 6.16445C40.9938 2.70679 49.1479 0.943812 57.391 1.00136L57.2737 17.801C51.5035 17.7607 45.7956 18.9948 40.5575 21.4151C35.3193 23.8355 30.6801 27.3824 26.9711 31.8027L14.1015 21.0039Z"
            fill="rgb(38,111,232)"
          />
        </g>
      </svg>
      <div className="da-example-donut-center">
        <span className="da-example-donut-label">数码产品</span>
        <span className="da-example-donut-value">16%</span>
      </div>
    </div>
  );
}

/** Decorative result table thumbnail. */
function TableThumb() {
  const head = ['时间', '数码产品 (万件)', '家用电器 (万件)', '差值 (万件)'];
  const rows = [
    ['全年', '928.5', '672.8', '255.7'],
    ['1月', '31.2', '18.6', '12.6'],
  ];
  return (
    <table className="da-example-table">
      <thead>
        <tr>
          {head.map(h => (
            <th key={h}>{h}</th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map(r => (
          <tr key={r[0]}>
            {r.map((c, i) => (
              <td key={i}>{c}</td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

interface Example {
  title: string;
  thumb: React.ReactNode;
}

const EXAMPLES: Example[] = [
  {
    title: '根据近三年服装鞋帽产品每天的销售额情况，使用 Prophet 算法预测接下来一年该类产品的销售额',
    thumb: <TrendThumb />,
  },
  {
    title: '使用饼状图帮我分析一下各类产品的销量分布',
    thumb: <DonutThumb />,
  },
  {
    title: '25年数码产品和家用电器销售额差值是多少，保留两位小数',
    thumb: <TableThumb />,
  },
];

export default function LandingExamples() {
  return (
    <div className="da-landing-examples" aria-label="问题示例">
      {EXAMPLES.map(ex => (
        <div className="da-example-card" key={ex.title}>
          <div className="da-example-title">
            <SparkIcon />
            <span>{ex.title}</span>
          </div>
          <div className="da-example-visual">{ex.thumb}</div>
        </div>
      ))}
    </div>
  );
}
