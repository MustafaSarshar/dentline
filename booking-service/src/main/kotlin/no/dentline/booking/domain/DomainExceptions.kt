package no.dentline.booking.domain

/** Machine-readable reason carried on every 409 response. */
enum class ConflictCode {
    SLOT_TAKEN,
    OUTSIDE_WORKING_HOURS,
    IN_THE_PAST,
    ILLEGAL_TRANSITION,
    OFFER_NOT_ACTIVE,
    OFFER_ALREADY_PENDING,
    NO_MATCHING_SLOT,
}

/** A request that is well-formed but conflicts with the current state. Mapped to HTTP 409. */
open class DomainConflictException(val code: ConflictCode, message: String) : RuntimeException(message)

class IllegalTransitionException(val from: AppointmentStatus, val to: AppointmentStatus) :
    DomainConflictException(ConflictCode.ILLEGAL_TRANSITION, "Cannot move an appointment from $from to $to")

/** Mapped to HTTP 404. */
class NotFoundException(entity: String, id: Any) : RuntimeException("$entity $id not found")
