import { describe, expect, it } from 'vitest';
import { validatePatient } from './DetailsScreen';

/** The client mirrors the server's rules so the patient sees the error before the round trip. */
describe('patient form validation', () => {
  const valid = { name: 'Ingrid Bakken', phone: '+47 913 44 208', email: 'ingrid.bakken@gmail.com' };

  it('accepts a filled-in form', () => {
    expect(validatePatient(valid)).toEqual({});
  });

  it('asks for a full name', () => {
    expect(validatePatient({ ...valid, name: 'I' }).name).toBe('Please enter your full name');
    expect(validatePatient({ ...valid, name: '  ' }).name).toBe('Please enter your full name');
  });

  it('counts digits in the phone number, ignoring spaces and the country code plus', () => {
    expect(validatePatient({ ...valid, phone: '12345' }).phone).toBe('Enter a phone number we can text');
    expect(validatePatient({ ...valid, phone: '+47 913 44 208' }).phone).toBeUndefined();
    expect(validatePatient({ ...valid, phone: '91344208' }).phone).toBeUndefined();
  });

  it('wants something that looks like an email address', () => {
    expect(validatePatient({ ...valid, email: 'not-an-email' }).email).toBe('Enter a valid email address');
    expect(validatePatient({ ...valid, email: 'a@b' }).email).toBe('Enter a valid email address');
    expect(validatePatient({ ...valid, email: 'a@b.no' }).email).toBeUndefined();
  });

  it('reports every bad field at once, so the form can highlight them together', () => {
    expect(Object.keys(validatePatient({ name: '', phone: '1', email: 'x' })).sort()).toEqual(['email', 'name', 'phone']);
  });
});
