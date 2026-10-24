
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    vus: 1,
    iterations: 5,
};

export default function () {
    const response = http.get('http://localhost:8080/proxy/hello');
    check(response, {'returns 401': (r) => r.status === 401,});
    sleep(1);
}
