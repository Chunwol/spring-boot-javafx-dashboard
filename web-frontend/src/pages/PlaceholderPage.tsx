export function PlaceholderPage({ title }: { title: string }) {
  return (
    <section className="content-section">
      <header className="page-header"><div><h1>{title}</h1></div></header>
      <div className="page-state"><p>화면을 준비하고 있습니다.</p></div>
    </section>
  )
}
