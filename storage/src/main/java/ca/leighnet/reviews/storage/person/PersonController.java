package ca.leighnet.reviews.storage.person;

import ca.leighnet.reviews.storage.jsonld.Hydra;
import ca.leighnet.reviews.storage.jsonld.HydraCollection;
import ca.leighnet.reviews.storage.jsonld.JsonLdContext;
import ca.leighnet.reviews.storage.web.HttpCaching;
import ca.leighnet.reviews.storage.web.Preconditions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/people",
		produces = { JsonLdContext.MEDIA_TYPE_VALUE, MediaType.APPLICATION_JSON_VALUE })
class PersonController {

	private final PersonService people;

	private final Hydra hydra;

	PersonController(PersonService people, Hydra hydra) {
		this.people = people;
		this.hydra = hydra;
	}

	/** Registered people, oldest first, without their emails. */
	@GetMapping
	ResponseEntity<HydraCollection<PersonDoc>> list(@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
			@RequestParam(defaultValue = "0") @Min(0) @Max(Hydra.MAX_OFFSET) int offset) {
		return HttpCaching.shared(hydra.collection(people.list(limit, offset)));
	}

	@PostMapping(consumes = { JsonLdContext.MEDIA_TYPE_VALUE, MediaType.APPLICATION_JSON_VALUE })
	ResponseEntity<PersonDoc> register(@Valid @RequestBody PersonDoc person) {
		var created = people.register(person);
		return HttpCaching.created(created, created.body().id());
	}

	/** Private: it answers with the caller, and shows their email. */
	@GetMapping("/me")
	ResponseEntity<PersonDoc> me() {
		return HttpCaching.okPrivate(people.readMe());
	}

	/** The same for every caller (no email), so shared caches may keep it. */
	@GetMapping("/{id}")
	ResponseEntity<PersonDoc> get(@PathVariable long id) {
		return HttpCaching.ok(people.read(id));
	}

	@PutMapping(path = "/{id}", consumes = { JsonLdContext.MEDIA_TYPE_VALUE, MediaType.APPLICATION_JSON_VALUE })
	ResponseEntity<PersonDoc> update(@PathVariable long id, @Valid @RequestBody PersonDoc person,
			Preconditions preconditions) {
		var updated = people.update(id, person, preconditions);
		return HttpCaching.updated(updated, updated.body().id());
	}

	@DeleteMapping("/{id}")
	ResponseEntity<Void> delete(@PathVariable long id, Preconditions preconditions) {
		people.delete(id, preconditions);
		return ResponseEntity.noContent().build();
	}

}
