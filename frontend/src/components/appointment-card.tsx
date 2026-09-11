import {
  Appointment,
  statusLabel,
  dayLabel,
  timeLabel,
} from "@/lib/appointments";
import { won } from "@/lib/api";

export function AppointmentCard({
  appointment: a,
  children,
}: {
  appointment: Appointment;
  children?: React.ReactNode;
}) {
  return (
    <article className="appointment-card">
      <div className="booking-card-top">
        <div>
          <span className="muted">{dayLabel(a.startsAt)}</span>
          <h2>
            {timeLabel(a.startsAt)} – {timeLabel(a.endsAt)}
          </h2>
        </div>
        <span className={`booking-status status-${a.status.toLowerCase()}`}>
          {statusLabel[a.status]}
        </span>
      </div>
      <p>
        <strong>{a.vehicleLabel}</strong>{" "}
        <span className="plate">{a.plateNumber}</span>
      </p>
      <ul className="booking-items">
        {a.items.map((item) => (
          <li key={item.serviceId}>
            <span>{item.name}</span>
            <span>
              {item.durationMinutes}분 · {won(item.laborPrice)}
            </span>
          </li>
        ))}
      </ul>
      <div className="booking-total">
        <span>예상 공임 · {a.durationMinutes}분</span>
        <strong>{won(a.totalLaborPrice)}</strong>
      </div>
      <p className="booking-caption">{a.workBayName} · 부품 비용 별도</p>
      {a.notes && <p className="booking-notes">{a.notes}</p>}
      {children}
    </article>
  );
}
