
import http from 'k6/http';
import { check } from 'k6';

export const options = {
    vus: 1,
    iterations: 20,
};

export default function () {
    const response = http.get(
        'http://localhost:8080/proxy/hello',
        {
            headers: {
                'X-API-Key': __ENV.API_KEY,
            },
        }
    );

    check(response, {
        'returns 200 or 429': (r) =>
            r.status === 200 || r.status === 429,
    });

    console.log(`HTTP status: ${response.status}`);
}
