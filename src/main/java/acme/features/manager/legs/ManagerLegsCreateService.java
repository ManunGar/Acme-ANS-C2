
package acme.features.manager.legs;

import java.util.Collection;
import java.util.Date;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;

import acme.client.components.basis.AbstractRealm;
import acme.client.components.models.Dataset;
import acme.client.components.views.SelectChoices;
import acme.client.helpers.PrincipalHelper;
import acme.client.services.AbstractGuiService;
import acme.client.services.GuiService;
import acme.constraints.LegsValidator;
import acme.entities.Aircrafts.Aircraft;
import acme.entities.Aircrafts.AircraftRepository;
import acme.entities.Airports.Airport;
import acme.entities.Airports.AirportRepository;
import acme.entities.Flight.Flight;
import acme.entities.Flight.FlightRepository;
import acme.entities.Legs.Legs;
import acme.entities.Legs.LegsStatus;
import acme.realms.AirlineManager;

@GuiService
public class ManagerLegsCreateService extends AbstractGuiService<AirlineManager, Legs> {
	// Internal state ---------------------------------------------------------

	@Autowired
	private ManagerLegsRepository	repository;

	@Autowired
	private FlightRepository		flightRepository;

	@Autowired
	private AircraftRepository		aircraftRepository;

	@Autowired
	private AirportRepository		airportRepository;

	// AbstractGuiService interface -------------------------------------------


	@Override
	public void authorise() {
		int id;
		Flight flight;
		int managerId = super.getRequest().getPrincipal().getActiveRealm().getUserAccount().getId();
		boolean isDeparture = true;
		boolean isArrival = true;
		boolean isDraftMode = true;
		boolean isAircraft = true;
		boolean status;

		try {
			id = super.getRequest().getData("flightId", int.class);
			flight = this.flightRepository.findFlightById(id);
			status = flight.getManager().getUserAccount().getId() == managerId;
			isDraftMode = flight.getDraftMode();

			Collection<Aircraft> aircrafts = this.aircraftRepository.findAllAircarftByAirlineId(flight.getManager().getAirline().getId());
			Collection<Airport> airports = this.airportRepository.findAllAirport();

			if (super.getRequest().hasData("id")) {

				Integer departure = super.getRequest().getData("departureAirport", int.class);
				Integer arrival = super.getRequest().getData("arrivalAirport", int.class);
				if (departure != 0) {
					Airport ad = this.airportRepository.findAirportById(departure);
					isDeparture = airports.contains(ad);
				}
				if (arrival != 0) {
					Airport aa = this.airportRepository.findAirportById(arrival);
					isArrival = airports.contains(aa);
				}

				Integer aircraft = super.getRequest().getData("aircraft", int.class);
				if (aircraft != 0) {
					Aircraft a = this.aircraftRepository.findAircraftById(aircraft);
					isAircraft = aircrafts.contains(a);
				}
			}
		} catch (Throwable E) {
			status = false;
		}

		super.getResponse().setAuthorised(status && isDraftMode && isDeparture && isArrival && isAircraft);
	}

	@Override
	public void load() {
		Legs leg;
		int flightId = super.getRequest().getData("flightId", int.class);
		super.getResponse().addGlobal("flightId", flightId);
		AbstractRealm principal = super.getRequest().getPrincipal().getActiveRealm();
		Flight flight = this.flightRepository.findFlightById(flightId);
		List<Legs> legs = (List<Legs>) this.repository.findAllByFlightId(flightId);
		legs = legs.size() > 0 ? LegsValidator.sortLegsByDeparture(legs) : legs;

		leg = new Legs();
		leg.setStatus(LegsStatus.ON_TYME);
		leg.setDraftMode(true);
		leg.setFlight(flight);
		leg.setDepartureAirport(legs.size() > 0 ? legs.get(legs.size() - 1).getArrivalAirport() : null);
		leg.setDeparture(legs.size() > 0 ? legs.get(legs.size() - 1).getArrival() : null);
		String iataCode = flight.getManager().getAirline().getIATAcode();
		leg.setFlightNumber(iataCode);

		super.getBuffer().addData(leg);
	}

	@Override
	public void bind(final Legs leg) {
		super.bindObject(leg, "departure", "arrival", "status", "departureAirport", "arrivalAirport", "aircraft", "flight", "flightNumber");

	}

	@Override
	public void validate(final Legs leg) {
		// No haya nada nulo
		if (leg.getAircraft() == null || leg.getDeparture() == null || leg.getArrival() == null || leg.getDepartureAirport() == null || leg.getArrivalAirport() == null)
			super.state(false, "*", "acme.validation.NotNull.message");
		else {
			// Comprobar IATACode
			Flight flight = this.flightRepository.findFlightById(leg.getFlight().getId());
			super.state(leg.getFlightNumber().substring(0, 3).equals(flight.getManager().getAirline().getIATAcode()), "flightNumber", "acme.validation.legs.iata.message");
			Legs numberLeg = this.repository.findLegByFlightNumber(leg.getFlightNumber());
			super.state(numberLeg == null, "flightNumber", "acme.validation.legs.iata.repeat.message");
			//Fechas no pueden estar en pasado
			super.state(!leg.getArrival().before(new Date()), "arrival", "acme.validation.legs.dates.arrival");
			super.state(!leg.getDeparture().before(new Date()), "departure", "acme.validation.legs.dates.departure");
			// Fechas salida y llegada bien ordenada
			boolean valid = leg.getArrival().before(leg.getDeparture());
			super.state(!valid, "departure", "acme.validation.legs.dates.message");
			// Aeropuerto salida y llegada no sean iguales
			Airport departureAirport = leg.getDepartureAirport();
			Airport arrivalAirport = leg.getArrivalAirport();
			super.state(!departureAirport.equals(arrivalAirport), "departureAirport", "acme.validation.leg.airport.message");

			boolean correctLeg = true;
			List<Legs> legs = (List<Legs>) this.repository.findAllByFlightId(leg.getFlight().getId());
			legs.add(leg);
			legs = LegsValidator.sortLegsByDeparture(legs);
			for (int i = 0; i < legs.size() - 1 && correctLeg; i++) {
				// Fecha salida y llegada de dos legs ordenados coincidan
				if (legs.get(i).getArrival().after(legs.get(i + 1).getDeparture())) {
					correctLeg = false;
					super.state(correctLeg, "departure", "acme.validation.legs.departure.message");
				}
				// Aeropuerto salida y llegada de dos legs ordenados no coincidan
				if (!legs.get(i).getArrivalAirport().equals(legs.get(i + 1).getDepartureAirport())) {
					correctLeg = false;
					super.state(correctLeg, "departureAirport", "acme.validation.legs.departureAirportmessage");
				}
			}
			// El avión seleccionado no esta siendo usado en otro tramo
			List<Legs> overlappingLegs = (List<Legs>) this.repository.findAllOverlappingIntervalAndAirline(leg.getDeparture(), leg.getArrival(), leg.getAircraft().getId(), leg.getFlight().getManager().getAirline().getId());
			if (!overlappingLegs.isEmpty())
				super.state(false, "aircraft", "acme.validation.legs.aircraft");
		}
	}

	@Override
	public void perform(final Legs leg) {
		this.repository.save(leg);
	}

	@Override
	public void unbind(final Legs leg) {
		Dataset dataset;
		SelectChoices choices;
		SelectChoices flightChoices;
		SelectChoices departureAirportChoices;
		SelectChoices arrivalAirportChoices;
		SelectChoices aircraftChoices;

		Collection<Flight> flights = this.flightRepository.findAllFlight();
		flightChoices = SelectChoices.from(flights, "highlights", leg.getFlight());
		Collection<Airport> airports = this.airportRepository.findAllAirport();
		Collection<Aircraft> aircrafts = this.aircraftRepository.findAllAircarftByAirlineId(leg.getFlight().getManager().getAirline().getId());
		departureAirportChoices = SelectChoices.from(airports, "city", leg.getDepartureAirport());
		arrivalAirportChoices = SelectChoices.from(airports, "city", leg.getArrivalAirport());
		aircraftChoices = SelectChoices.from(aircrafts, "model", leg.getAircraft());
		choices = SelectChoices.from(LegsStatus.class, leg.getStatus());

		dataset = super.unbindObject(leg, "departure", "arrival", "draftMode");
		dataset.put("flightNumber", leg.getFlightNumber());
		dataset.put("status", choices);
		dataset.put("flight", leg.getFlight());
		dataset.put("flights", flightChoices);
		dataset.put("departureAirport", departureAirportChoices.getSelected().getKey());
		dataset.put("departureAirports", departureAirportChoices);
		dataset.put("arrivalAirport", arrivalAirportChoices.getSelected().getKey());
		dataset.put("arrivalAirports", arrivalAirportChoices);
		dataset.put("aircraft", aircraftChoices.getSelected().getKey());
		dataset.put("aircrafts", aircraftChoices);

		super.getResponse().addData(dataset);
	}

	@Override
	public void onSuccess() {
		if (super.getRequest().getMethod().equals("POST"))
			PrincipalHelper.handleUpdate();
	}

}
